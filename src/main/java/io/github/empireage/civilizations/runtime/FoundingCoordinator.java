package io.github.empireage.civilizations.runtime;

import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.config.ResourceText;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.HomeLocation;
import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.domain.ResourceKey;
import io.github.empireage.civilizations.service.economy.EconomyService;
import io.github.empireage.civilizations.service.lifecycle.CivilizationLifecycleService;
import io.github.empireage.civilizations.service.lifecycle.FoundingInventory;
import io.github.empireage.civilizations.service.lifecycle.LifecycleModels.CreateRequest;
import io.github.empireage.civilizations.service.lifecycle.LifecycleModels.CreateResult;
import io.github.empireage.civilizations.service.progression.CivicItemService;
import io.github.empireage.civilizations.service.progression.WorkOrderService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates main-thread inventory/Vault compensation with the atomic MySQL founding transaction. */
public final class FoundingCoordinator implements Listener, AutoCloseable {
    private static final long CLOSE_DRAIN_MILLIS = 5_000L;
    private static final String UNCERTAIN_CREATE_PREFIX = "Could not create the civilization";

    private final JavaPlugin plugin;
    private final Settings settings;
    private final CivicItemService civicItems;
    private final CivilizationLifecycleService lifecycle;
    private final EconomyService economy;
    private final WorkOrderService workOrders;
    private final Map<UUID, FoundingAttempt> active = new ConcurrentHashMap<>();
    private final ItemRefunds refunds;
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public FoundingCoordinator(JavaPlugin plugin, Settings settings, CivicItemService civicItems,
                               CivilizationLifecycleService lifecycle, EconomyService economy) {
        this(plugin, settings, civicItems, lifecycle, economy, null);
    }

    public FoundingCoordinator(JavaPlugin plugin, Settings settings, CivicItemService civicItems,
                               CivilizationLifecycleService lifecycle, EconomyService economy,
                               WorkOrderService workOrders) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.civicItems = Objects.requireNonNull(civicItems, "civicItems");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.workOrders = workOrders;
        this.refunds = new ItemRefunds(plugin, "founding");
    }

    public CompletableFuture<OperationResult> create(Player player, String name, boolean externallyProtected) {
        Objects.requireNonNull(player, "player");
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Founding must begin on the primary server thread");
        if (!accepting.get() || !plugin.isEnabled()) {
            return CompletableFuture.completedFuture(OperationResult.denied("The plugin is shutting down."));
        }
        UUID founderId = player.getUniqueId();
        restorePending(founderId, player);
        if (refunds.hasPending(founderId)) return CompletableFuture.completedFuture(
            OperationResult.denied("Founding materials from a previous failed attempt must be restored before trying again."));
        if (active.containsKey(founderId)) return CompletableFuture.completedFuture(
            OperationResult.denied("A civilization founding attempt is already being processed."));
        if (player.getGameMode() == GameMode.CREATIVE) return CompletableFuture.completedFuture(
            OperationResult.denied("Civilizations cannot be founded with creative-mode civic materials."));
        Location location = player.getLocation().clone();
        if (!settings.worlds().allowed(location.getWorld().getName())) return CompletableFuture.completedFuture(
            OperationResult.denied("Civilizations cannot be founded in this world."));
        String biome = location.getBlock().getBiome().getKey().toString();
        if (settings.worlds().blacklistedBiomes().contains(biome.toUpperCase(java.util.Locale.ROOT))
            || settings.worlds().blacklistedBiomes().contains(location.getBlock().getBiome().name())) {
            return CompletableFuture.completedFuture(OperationResult.denied("Civilizations cannot be founded in this biome."));
        }
        Map<ResourceKey, Long> inventoryCounts = civicItems.countValid(Arrays.asList(player.getInventory().getStorageContents()));
        boolean enough = settings.founding().materialCost().entrySet().stream()
            .allMatch(entry -> inventoryCounts.getOrDefault(entry.getKey(), 0L) >= entry.getValue());
        if (!enough) return CompletableFuture.completedFuture(OperationResult.denied(
            "You do not have the required founding civic materials: "
                + ResourceText.cost(civicItems.catalog(), settings.founding().materialCost()) + "."));
        InventoryDebit debit = removeCivicItems(player, settings.founding().materialCost());
        if (debit == null) return CompletableFuture.completedFuture(OperationResult.denied(
            "You do not have the required founding civic materials: "
                + ResourceText.cost(civicItems.catalog(), settings.founding().materialCost()) + "."));

        CompletableFuture<FoundingOutcome> outcome = new CompletableFuture<>();
        CompletableFuture<OperationResult> result = new CompletableFuture<>();
        FoundingAttempt attempt = new FoundingAttempt(player, debit, outcome, result, new AtomicBoolean(false));
        FoundingAttempt previous = active.putIfAbsent(founderId, attempt);
        if (previous != null) {
            refunds.refundable(debit.ticket());
            refunds.restore(player);
            return CompletableFuture.completedFuture(OperationResult.denied(
                "A civilization founding attempt is already being processed."));
        }

        String founderName = player.getName();
        ChunkKey capital = new ChunkKey(location.getWorld().getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        HomeLocation home = new HomeLocation(location.getWorld().getUID(), location.getX(), location.getY(), location.getZ(),
            location.getYaw(), location.getPitch());
        settle(founderId, founderName, name, location.getWorld().getName(), biome, capital, home,
            externallyProtected, inventoryCounts)
            .whenComplete((value, failure) -> {
                FoundingOutcome settled = failure == null ? value : FoundingOutcome.retain(OperationResult.denied(
                    "The founding outcome is unknown; materials were retained to prevent duplication: " + rootMessage(failure)));
                try {
                    if (settled.restore()) refunds.refundable(debit.ticket());
                    else if (settled.result().success()) refunds.complete(debit.ticket());
                } catch (RuntimeException journalFailure) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Could not queue founding item refund " + debit.ticket().operation(), journalFailure);
                    settled = FoundingOutcome.retain(OperationResult.denied(
                        "Founding item recovery " + debit.ticket().operation() + " requires administrator review."));
                }
                outcome.complete(settled);
            });
        outcome.whenComplete((value, failure) -> finishAttempt(attempt, failure == null ? value
            : FoundingOutcome.retain(OperationResult.denied(
                "The founding outcome is unknown; materials were retained to prevent duplication: " + rootMessage(failure)))));
        return result;
    }

    private CompletableFuture<FoundingOutcome> settle(UUID founderId, String founderName, String name,
                                                       String worldName, String biome, ChunkKey capital, HomeLocation home,
                                                       boolean externallyProtected,
                                                       Map<ResourceKey, Long> inventoryCounts) {
        return economy.reserveCharge(founderId, settings.founding().moneyCost(), "CIVILIZATION_FOUNDING")
            .handle((charge, chargeFailure) -> {
                if (chargeFailure != null) {
                    return CompletableFuture.completedFuture(FoundingOutcome.restore(OperationResult.denied(
                        "Founding payment could not be confirmed: " + rootMessage(chargeFailure)
                            + ". Any ambiguous Vault operation is locked for administrator review.")));
                }
                if (!charge.result().success()) {
                    return CompletableFuture.completedFuture(FoundingOutcome.restore(charge.result()));
                }
                CreateRequest request = new CreateRequest(founderId, founderName, name, capital,
                    worldName, biome, home, externallyProtected, new FoundingInventory(inventoryCounts),
                    settings.founding().moneyCost(), charge.operationId());
                return lifecycle.create(request)
                    .handle((createResult, createFailure) -> new LifecycleResponse(createResult, createFailure))
                    .thenCompose(response -> {
                        if (response.failure() != null) {
                            return reconcileFailedFounding(founderId, charge,
                                "Founding failed: " + rootMessage(response.failure()), true);
                        }
                        CreateResult created = response.result();
                        if (!created.result().success()) {
                            return reconcileFailedFounding(founderId, charge, created.result().message(),
                                uncertainLifecycleDenial(created.result()));
                        }
                        CompletableFuture<FoundingOutcome> paid = economy.completeCharge(charge, created.civilizationId())
                            .handle((payment, completionFailure) -> FoundingOutcome.retain(
                                completionFailure == null && payment.success() ? created.result() : OperationResult.ok(
                                    created.result().message() + " The payment completion record requires administrator review.")));
                        if (workOrders == null) return paid;
                        return paid.thenCompose(outcome -> workOrders.ensureOrders(created.civilizationId(), Instant.now())
                            .handle((orders, orderFailure) -> outcome));
                    });
            }).thenCompose(value -> value);
    }

    private CompletableFuture<FoundingOutcome> reconcileFailedFounding(UUID founderId, EconomyService.Charge charge,
                                                                        String failureMessage,
                                                                        boolean transactionOutcomeUncertain) {
        if (charge.operationId() == null) {
            return CompletableFuture.completedFuture(transactionOutcomeUncertain
                ? FoundingOutcome.retain(OperationResult.denied(failureMessage
                    + " Founding materials were retained because the database commit outcome is unknown."))
                : FoundingOutcome.restore(OperationResult.denied(failureMessage)));
        }
        return economy.attachedFoundingCivilization(charge.operationId())
            .handle((civilizationId, lookupFailure) -> new FoundingAttachment(civilizationId, lookupFailure))
            .thenCompose(attachment -> {
                if (attachment.lookupFailure() != null) {
                    // A commit acknowledgement can fail after MySQL committed.
                    // Keep both sides held until the durable row can be read.
                    return CompletableFuture.completedFuture(FoundingOutcome.retain(OperationResult.denied(failureMessage
                        + " Payment and founding materials were retained because the commit outcome is unknown; "
                        + "an administrator must reconcile operation " + charge.operationId() + ".")));
                }
                if (attachment.civilizationId() != null) {
                    return economy.completeCharge(charge, attachment.civilizationId()).handle((payment, completionFailure) ->
                        FoundingOutcome.retain(OperationResult.ok("The civilization was committed successfully. Payment operation "
                            + charge.operationId() + (completionFailure == null && payment.success()
                            ? " was finalized." : " remains visible for administrator review."))));
                }
                return economy.refundCharge(charge, founderId).handle((refund, refundFailure) -> {
                    String suffix = refundFailure != null
                        ? " The currency refund outcome is unknown and operation " + charge.operationId()
                            + " requires administrator review."
                        : refund.success() ? "" : " " + refund.message();
                    return FoundingOutcome.restore(OperationResult.denied(failureMessage + suffix));
                });
            });
    }

    private void finishAttempt(FoundingAttempt attempt, FoundingOutcome outcome) {
        UUID playerId = attempt.player().getUniqueId();
        if (!outcome.restore()) {
            if (!attempt.finished().compareAndSet(false, true)) return;
            active.remove(playerId, attempt);
            attempt.result().complete(outcome.result());
            return;
        }
        Runnable finish = () -> finishRestorable(attempt, outcome.result());
        if (Bukkit.isPrimaryThread()) {
            finish.run();
            return;
        }
        if (!accepting.get()) return; // close() performs the restoration while player data is loaded.
        try {
            Bukkit.getScheduler().runTask(plugin, finish);
        } catch (RuntimeException schedulerUnavailable) {
            finishPendingOffThread(attempt, outcome.result());
        }
    }

    private void finishRestorable(FoundingAttempt attempt, OperationResult result) {
        if (!attempt.finished().compareAndSet(false, true)) return;
        UUID playerId = attempt.player().getUniqueId();
        boolean restored = false;
        try {
            restored = restorePending(playerId);
        } catch (RuntimeException recoveryFailure) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                "Could not deliver founding item refund " + attempt.debit().ticket().operation(), recoveryFailure);
        }
        active.remove(playerId, attempt);
        attempt.result().complete(OperationResult.denied(result.message()
            + (restored ? " Founding materials were restored." : " Founding materials will be restored when you rejoin.")));
    }

    private void finishPendingOffThread(FoundingAttempt attempt, OperationResult result) {
        if (!attempt.finished().compareAndSet(false, true)) return;
        UUID playerId = attempt.player().getUniqueId();
        active.remove(playerId, attempt);
        attempt.result().complete(OperationResult.denied(result.message()
            + " Founding materials will be restored when you rejoin."));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (restorePending(event.getPlayer().getUniqueId(), event.getPlayer())) {
            event.getPlayer().sendMessage("Founding materials from a failed attempt were restored to your inventory.");
        }
    }

    /** Must run on the primary server thread. */
    public boolean restorePending(UUID playerId) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Inventory restoration must run on the primary server thread");
        Player player = Bukkit.getPlayer(playerId);
        return player != null && player.isOnline() && restorePending(playerId, player);
    }

    private boolean restorePending(UUID playerId, Player player) {
        return refunds.restore(player);
    }

    private InventoryDebit removeCivicItems(Player player, Map<ResourceKey, Long> costs) {
        Map<ResourceKey, Long> remaining = new LinkedHashMap<>(costs);
        List<Removed> removed = new ArrayList<>();
        ItemStack[] contents = Arrays.stream(player.getInventory().getStorageContents())
            .map(stack -> stack == null ? null : stack.clone()).toArray(ItemStack[]::new);
        for (int slot = 0; slot < contents.length && remaining.values().stream().anyMatch(value -> value > 0); slot++) {
            ItemStack stack = contents[slot];
            var key = civicItems.identify(stack);
            if (key.isEmpty()) continue;
            long needed = remaining.getOrDefault(key.get(), 0L);
            if (needed <= 0) continue;
            int take = (int) Math.min(needed, stack.getAmount());
            ItemStack copy = stack.clone();
            copy.setAmount(take);
            removed.add(new Removed(slot, copy));
            if (take == stack.getAmount()) contents[slot] = null;
            else stack.setAmount(stack.getAmount() - take);
            remaining.put(key.get(), needed - take);
        }
        if (remaining.values().stream().anyMatch(value -> value > 0)) return null;
        ItemRefunds.Ticket ticket = refunds.prepare(UUID.randomUUID(), player.getUniqueId(),
            removed.stream().map(Removed::item).toList());
        refunds.debit(player, ticket, contents);
        return new InventoryDebit(List.copyOf(removed), ticket);
    }

    /** Stops new founding debits and restores only attempts proven not to have committed. */
    @Override
    public void close() {
        if (!accepting.compareAndSet(true, false)) return;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_DRAIN_MILLIS);
        for (FoundingAttempt attempt : List.copyOf(active.values())) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) break;
            try {
                attempt.outcome().get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException ignored) {
                // Unresolved means unknown and must never be refunded.
            }
        }
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getOnlinePlayers().forEach(player -> refunds.restore(player));
            for (FoundingAttempt attempt : List.copyOf(active.values())) {
                FoundingOutcome outcome = attempt.outcome().getNow(null);
                if (outcome != null && outcome.restore()) finishRestorable(attempt, outcome.result());
            }
        }
        active.clear();
    }

    static boolean uncertainLifecycleDenial(OperationResult result) {
        return !result.success() && result.message().startsWith(UNCERTAIN_CREATE_PREFIX);
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private record Removed(int slot, ItemStack item) {
        private Removed {
            item = item.clone();
        }
    }

    private record InventoryDebit(List<Removed> removed, ItemRefunds.Ticket ticket) {
        private InventoryDebit {
            removed = List.copyOf(removed);
        }
    }

    private record FoundingAttachment(Long civilizationId, Throwable lookupFailure) {}
    private record LifecycleResponse(CreateResult result, Throwable failure) {}
    private record FoundingOutcome(OperationResult result, boolean restore) {
        static FoundingOutcome restore(OperationResult result) { return new FoundingOutcome(result, true); }
        static FoundingOutcome retain(OperationResult result) { return new FoundingOutcome(result, false); }
    }
    private record FoundingAttempt(Player player, InventoryDebit debit, CompletableFuture<FoundingOutcome> outcome,
                                   CompletableFuture<OperationResult> result, AtomicBoolean finished) {}
}
