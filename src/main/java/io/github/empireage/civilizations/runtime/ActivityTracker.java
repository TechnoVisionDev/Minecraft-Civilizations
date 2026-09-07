package io.github.empireage.civilizations.runtime;

import io.github.empireage.civilizations.service.lifecycle.CivilizationLifecycleService;
import io.github.empireage.civilizations.service.lifecycle.LifecycleModels.ActivityResult;
import io.github.empireage.civilizations.service.lifecycle.LifecycleModels.ActivityUpdate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ActivityTracker implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final CivilizationLifecycleService lifecycle;
    private final Map<UUID, Instant> observedSince = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> unsavedSince = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<?>> pendingWrites = new ConcurrentHashMap<>();
    private BukkitTask task;

    public ActivityTracker(JavaPlugin plugin, CivilizationLifecycleService lifecycle) {
        this.plugin = plugin;
        this.lifecycle = lifecycle;
    }

    public void start() {
        Instant now = Instant.now();
        Bukkit.getOnlinePlayers().forEach(player -> {
            observedSince.put(player.getUniqueId(), now);
            lifecycle.recordPresence(player.getUniqueId(), true, now);
            flush(player);
        });
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::flushOnline, 20L * 300, 20L * 300);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Instant now = Instant.now();
        observedSince.put(playerId, now);
        lifecycle.recordPresence(playerId, true, now);
        flush(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        lifecycle.recordPresence(playerId, false, Instant.now());
        flush(event.getPlayer()).whenComplete((ignored, error) -> {
            pendingWrites.computeIfPresent(playerId, (id, current) -> current.isDone() ? null : current);
            // A failed final write remains available if the player reconnects.
            // Successful writes remove their own retry marker.
        });
        observedSince.remove(playerId);
    }

    private void flushOnline() {
        Bukkit.getOnlinePlayers().forEach(this::flush);
    }

    private CompletableFuture<?> flush(Player player) {
        Instant now = Instant.now();
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        Instant since = observedSince.put(playerId, now);
        return pendingWrites.compute(playerId, (ignored, previous) -> {
            CompletableFuture<?> tail = previous == null ? CompletableFuture.completedFuture(null) : previous;
            return tail.handle((value, error) -> null).thenCompose(value -> {
                Instant retrySince = earlier(unsavedSince.get(playerId), since == null ? now : since);
                return lifecycle.updateActivity(new ActivityUpdate(playerId, playerName, retrySince, now))
                    .handle((result, error) -> rememberActivityOutcome(playerId, retrySince, result, error));
            });
        });
    }

    private ActivityResult rememberActivityOutcome(UUID playerId, Instant attemptedSince,
                                                   ActivityResult result, Throwable error) {
        if (error != null) {
            unsavedSince.merge(playerId, attemptedSince, ActivityTracker::earlier);
            throw new java.util.concurrent.CompletionException(error);
        }
        if (result.result().success()) {
            unsavedSince.remove(playerId, attemptedSince);
        } else {
            unsavedSince.merge(playerId, attemptedSince, ActivityTracker::earlier);
        }
        return result;
    }

    private static Instant earlier(Instant first, Instant second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isBefore(second) ? first : second;
    }

    @Override
    public void close() {
        if (task != null) task.cancel();
        task = null;
        flushOnline();
        CompletableFuture<?>[] writes = new ArrayList<>(pendingWrites.values()).toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(writes).get(5, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            plugin.getLogger().warning("Timed out while saving final civilization activity intervals during shutdown.");
        } catch (Exception failure) {
            plugin.getLogger().warning("Could not save every final civilization activity interval: " + failure.getMessage());
        }
        pendingWrites.clear();
        observedSince.clear();
        unsavedSince.clear();
    }
}
