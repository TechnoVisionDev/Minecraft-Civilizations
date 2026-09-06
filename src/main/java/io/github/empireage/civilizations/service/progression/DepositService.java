package io.github.empireage.civilizations.service.progression;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.database.AuditLog;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.database.Sql;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.ResourceKey;
import io.github.empireage.civilizations.runtime.ItemRefunds;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Main-thread inventory removal followed by an atomic asynchronous stockpile commit. */
public final class DepositService implements AutoCloseable {
    private static final long CLOSE_DRAIN_MILLIS = 5_000L;

    public enum Scope { HAND, ALL }

    private final JavaPlugin plugin;
    private final Database database;
    private final StateCache cache;
    private final CivicItemService items;
    private final StockpileService stockpile;
    private final String serverId;
    private final Map<UUID, UUID> depositsInFlight = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveDeposit> activeDeposits = new ConcurrentHashMap<>();
    private final ItemRefunds refunds;
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public DepositService(JavaPlugin plugin, Database database, StateCache cache, CivicItemService items,
                          StockpileService stockpile, String serverId) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.database = Objects.requireNonNull(database, "database");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.items = Objects.requireNonNull(items, "items");
        this.stockpile = Objects.requireNonNull(stockpile, "stockpile");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.refunds = new ItemRefunds(plugin, "deposits");
    }

    public CompletableFuture<DepositResult> deposit(Player player, Scope scope) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(scope, "scope");
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Deposits must begin on the primary server thread");
        if (!accepting.get()) {
            return CompletableFuture.completedFuture(DepositResult.denied("Civic deposits are stopping for server shutdown."));
        }
        refunds.restore(player);
        if (refunds.hasPending(player.getUniqueId())) return CompletableFuture.completedFuture(
            DepositResult.denied("Free inventory space to collect your pending refund before depositing again."));
        if (player.getGameMode() == GameMode.CREATIVE && !items.catalog().creativeDeposits()) {
            return CompletableFuture.completedFuture(DepositResult.denied("Creative-mode civic deposits are disabled."));
        }
        Member member = cache.snapshot().member(player.getUniqueId());
        if (member == null) return CompletableFuture.completedFuture(DepositResult.denied("You are not a civilization member."));
        UUID operationId = UUID.randomUUID();
        if (depositsInFlight.putIfAbsent(player.getUniqueId(), operationId) != null) {
            return CompletableFuture.completedFuture(DepositResult.denied("A deposit is already being processed."));
        }
        RestorationPayload payload;
        try {
            payload = remove(player, scope, operationId, member.civilizationId());
        } catch (RuntimeException failure) {
            depositsInFlight.remove(player.getUniqueId(), operationId);
            return CompletableFuture.failedFuture(failure);
        }
        if (payload.resources().isEmpty()) {
            depositsInFlight.remove(player.getUniqueId(), operationId);
            return CompletableFuture.completedFuture(DepositResult.denied("No authentic current-version civic materials were found."));
        }

        CompletableFuture<DepositResult> result = new CompletableFuture<>();
        CompletableFuture<CommitOutcome> outcome = new CompletableFuture<>();
        ActiveDeposit active = new ActiveDeposit(player, payload, outcome, result);
        activeDeposits.put(operationId, active);
        database.transaction(connection -> {
            StockpileService.lockCivilization(connection, payload.civilizationId());
            try (var statement = Sql.prepare(connection,
                "SELECT civ_id FROM civ_members WHERE player_uuid = ? FOR UPDATE", payload.playerId());
                 ResultSet membership = statement.executeQuery()) {
                if (!membership.next() || membership.getLong(1) != payload.civilizationId())
                    throw new IllegalStateException("Civilization membership changed during deposit");
            }
            stockpile.applyInTransaction(connection, payload.operationId(), payload.civilizationId(), payload.playerId(),
                payload.resources(), false, "DEPOSIT", "player", payload.playerId().toString());
            AuditLog.write(connection, payload.civilizationId(), payload.playerId(), "stockpile.deposit", "operation",
                payload.operationId().toString(), Map.of("resources", serialize(payload.resources())), serverId);
            return new Commit(0, 0);
        }).whenComplete((commit, error) -> {
            if (error == null) {
                outcome.complete(CommitOutcome.committed(commit));
                return;
            }
            // A failed commit acknowledgement is not proof of rollback. Read the
            // immutable operation ledger before deciding whether inventory may be
            // returned; an unavailable reconciliation read remains UNKNOWN.
            committed(payload.operationId()).whenComplete((wasCommitted, lookupError) -> {
                if (definitelyRolledBack(wasCommitted, lookupError)) {
                    try {
                        // Durable before scheduling Bukkit work or releasing the shutdown drain.
                        refunds.refundable(payload.ticket());
                        outcome.complete(CommitOutcome.failed(unwrap(error)));
                    } catch (RuntimeException journalFailure) {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE,
                            "Could not queue refund for deposit " + payload.operationId(), journalFailure);
                        outcome.complete(CommitOutcome.unknown(unwrap(error), journalFailure));
                    }
                } else if (lookupError == null && Boolean.TRUE.equals(wasCommitted)) {
                    outcome.complete(CommitOutcome.committed(new Commit(0, 0)));
                } else {
                    outcome.complete(CommitOutcome.unknown(unwrap(error), unwrap(lookupError)));
                }
            });
        });
        outcome.whenComplete((commitOutcome, impossibleError) -> finishDeposit(active,
            impossibleError == null ? commitOutcome : CommitOutcome.unknown(unwrap(impossibleError), null)));
        return result;
    }

    private CompletableFuture<Boolean> committed(UUID operationId) {
        return database.read(connection -> {
            try (var statement = Sql.prepare(connection,
                "SELECT 1 FROM stockpile_ledger WHERE operation_id = ? LIMIT 1", operationId);
                 ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        });
    }

    private void finishDeposit(ActiveDeposit active, CommitOutcome outcome) {
        RestorationPayload payload = active.payload();
        if (outcome.state() == CommitState.COMMITTED) {
            if (!active.finished().compareAndSet(false, true)) return;
            refunds.complete(payload.ticket());
            activeDeposits.remove(payload.operationId(), active);
            depositsInFlight.remove(payload.playerId(), payload.operationId());
            cache.refreshAfterMutation().exceptionally(refreshError -> null);
            long total = payload.resources().values().stream().mapToLong(Long::longValue).sum();
            active.result().complete(new DepositResult(true, "Deposited " + total + " civic materials.",
                payload.operationId(), payload.resources(), false, outcome.commit().knowledge()));
            return;
        }
        if (outcome.state() == CommitState.UNKNOWN) {
            if (!active.finished().compareAndSet(false, true)) return;
            activeDeposits.remove(payload.operationId(), active);
            depositsInFlight.remove(payload.playerId(), payload.operationId());
            active.result().complete(new DepositResult(false,
                "The deposit outcome could not be confirmed; removed items were retained to prevent duplication. "
                    + "Operation " + payload.operationId() + " requires administrator review.",
                payload.operationId(), payload.resources(), false, 0));
            return;
        }

        Runnable finish = () -> finishKnownFailure(active, outcome.failure());
        if (Bukkit.isPrimaryThread()) {
            finish.run();
            return;
        }
        if (!accepting.get()) return; // close() drains and performs the main-thread restoration itself.
        try {
            Bukkit.getScheduler().runTask(plugin, finish);
        } catch (RuntimeException schedulerUnavailable) {
            if (!active.finished().compareAndSet(false, true)) return;
            activeDeposits.remove(payload.operationId(), active);
            depositsInFlight.remove(payload.playerId(), payload.operationId());
            active.result().complete(new DepositResult(false,
                "Deposit failed; item restoration is pending your next login.", payload.operationId(),
                payload.resources(), false, 0));
        }
    }

    private void finishKnownFailure(ActiveDeposit active, Throwable error) {
        RestorationPayload payload = active.payload();
        if (!active.finished().compareAndSet(false, true)) return;
        activeDeposits.remove(payload.operationId(), active);
        depositsInFlight.remove(payload.playerId(), payload.operationId());
        boolean restored = false;
        try {
            restored = restorePending(payload.playerId());
        } catch (RuntimeException recoveryFailure) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                "Could not deliver pending item refund " + payload.operationId(), recoveryFailure);
        }
        String detail = error == null || error.getMessage() == null ? "storage rejected the transaction" : error.getMessage();
        active.result().complete(new DepositResult(false,
            "Deposit failed; removed items " + (restored ? "were restored." : "will be restored when you rejoin.")
                + " (" + detail + ")",
            payload.operationId(), payload.resources(), restored, 0));
    }

    /** Must run on the primary thread. Returns false when the player is offline. */
    public boolean restorePending(UUID playerId) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Inventory restoration must run on the primary thread");
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return false;
        return refunds.restore(player);
    }

    /** Stops new debits, waits briefly for storage outcomes, then restores only proven rollbacks. */
    @Override
    public void close() {
        if (!accepting.compareAndSet(true, false)) return;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_DRAIN_MILLIS);
        for (ActiveDeposit active : List.copyOf(activeDeposits.values())) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) break;
            try {
                active.outcome().get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException ignored) {
                // An unresolved outcome is deliberately retained, never refunded.
            }
        }

        // onDisable runs on the server thread while online player data is still
        // loaded. Do not try to mutate Bukkit inventory from any other thread.
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getOnlinePlayers().forEach(player -> refunds.restore(player));
            for (ActiveDeposit active : List.copyOf(activeDeposits.values())) {
                CommitOutcome outcome = active.outcome().getNow(null);
                if (outcome != null && outcome.state() == CommitState.FAILED) finishKnownFailure(active, outcome.failure());
            }
        }
        activeDeposits.clear();
        depositsInFlight.clear();
    }

    private RestorationPayload remove(Player player, Scope scope, UUID operationId, long civilizationId) {
        List<RemovedStack> removed = new ArrayList<>();
        Map<ResourceKey, Long> resources = new LinkedHashMap<>();
        ItemStack[] contents = player.getInventory().getStorageContents().clone();
        if (scope == Scope.HAND) {
            int slot = player.getInventory().getHeldItemSlot();
            removeSlot(contents, slot, removed, resources);
        } else {
            for (int slot = 0; slot < contents.length; slot++) removeSlot(contents, slot, removed, resources);
        }
        ItemRefunds.Ticket ticket = null;
        if (!resources.isEmpty()) {
            ticket = refunds.prepare(operationId, player.getUniqueId(), removed.stream().map(RemovedStack::item).toList());
            refunds.debit(player, ticket, contents);
        }
        return new RestorationPayload(operationId, player.getUniqueId(), civilizationId, Instant.now(),
            List.copyOf(removed), Map.copyOf(resources), ticket);
    }

    private void removeSlot(ItemStack[] contents, int slot, List<RemovedStack> removed, Map<ResourceKey, Long> resources) {
        ItemStack stack = contents[slot];
        ResourceKey key = items.identify(stack).orElse(null);
        if (key == null) return;
        ItemStack snapshot = stack.clone();
        removed.add(new RemovedStack(slot, snapshot));
        resources.merge(key, (long) stack.getAmount(), Math::addExact);
        contents[slot] = null;
    }

    private Map<String, Long> serialize(Map<ResourceKey, Long> resources) {
        Map<String, Long> values = new LinkedHashMap<>();
        resources.forEach((key, amount) -> values.put(key.serialized(), amount));
        return values;
    }

    private Throwable unwrap(Throwable error) {
        if (error == null) return null;
        Throwable cursor = error;
        while ((cursor instanceof CompletionException || cursor instanceof java.util.concurrent.ExecutionException)
            && cursor.getCause() != null) cursor = cursor.getCause();
        return cursor;
    }

    static boolean definitelyRolledBack(Boolean committed, Throwable reconciliationFailure) {
        return reconciliationFailure == null && Boolean.FALSE.equals(committed);
    }

    private record Commit(int knowledge, long contribution) {}

    private enum CommitState { COMMITTED, FAILED, UNKNOWN }

    private record CommitOutcome(CommitState state, Commit commit, Throwable failure, Throwable reconciliationFailure) {
        static CommitOutcome committed(Commit commit) {
            return new CommitOutcome(CommitState.COMMITTED, commit, null, null);
        }

        static CommitOutcome failed(Throwable failure) {
            return new CommitOutcome(CommitState.FAILED, null, failure, null);
        }

        static CommitOutcome unknown(Throwable failure, Throwable reconciliationFailure) {
            return new CommitOutcome(CommitState.UNKNOWN, null, failure, reconciliationFailure);
        }
    }

    private record ActiveDeposit(Player player, RestorationPayload payload, CompletableFuture<CommitOutcome> outcome,
                                 CompletableFuture<DepositResult> result, AtomicBoolean finished) {
        ActiveDeposit(Player player, RestorationPayload payload, CompletableFuture<CommitOutcome> outcome,
                      CompletableFuture<DepositResult> result) {
            this(player, payload, outcome, result, new AtomicBoolean(false));
        }
    }

    public record RemovedStack(int slot, ItemStack item) {
        public RemovedStack {
            item = item.clone();
        }
    }

    public record RestorationPayload(UUID operationId, UUID playerId, long civilizationId, Instant createdAt,
                                     List<RemovedStack> removed, Map<ResourceKey, Long> resources, ItemRefunds.Ticket ticket) {}

    public record DepositResult(boolean success, String message, UUID operationId, Map<ResourceKey, Long> resources,
                                boolean restored, int knowledgeAwarded) {
        public static DepositResult denied(String message) {
            return new DepositResult(false, message, null, Map.of(), false, 0);
        }
    }
}
