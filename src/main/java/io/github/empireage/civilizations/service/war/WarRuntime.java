package io.github.empireage.civilizations.service.war;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.War;
import io.github.empireage.civilizations.domain.WarState;
import io.github.empireage.civilizations.util.MainThreadExecutor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WarRuntime implements AutoCloseable {
    private final JavaPlugin plugin;
    private final StateCache cache;
    private final WarService wars;
    private final WarItems items;
    private final MainThreadExecutor mainThread;
    private final Map<Long, EnumSet<Reminder>> sentReminders = new HashMap<>();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private BukkitTask task;
    private BukkitTask cleanupTask;

    public WarRuntime(JavaPlugin plugin, StateCache cache, WarService wars, WarItems items, Settings.War settings) {
        this.plugin = plugin;
        this.cache = cache;
        this.wars = wars;
        this.items = items;
        this.mainThread = new MainThreadExecutor(plugin);
    }

    public void start(long periodTicks) {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, Math.max(1L, periodTicks));
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::recoverTemporaryBlocks, 40L, 1200L);
    }

    private void tick() {
        if (!cache.ready() || !busy.compareAndSet(false, true)) return;
        wars.reconcileTransitions().whenComplete((result, error) -> mainThread.run(() -> {
            try {
                if (error != null) {
                    plugin.getLogger().warning("War runtime tick failed: " + error.getMessage());
                    return;
                }
                updateReminders();
                result.activated().forEach(id -> Bukkit.broadcastMessage(ChatColor.RED
                    + "War #" + id + " is now active: fighting, territory breaking, and looting are allowed between participants."));
                result.resolved().forEach(id -> {
                    War endedWar = cache.snapshot().wars().get(id);
                    String truce = endedWar == null || endedWar.truceUntil() == null ? "the configured truce period"
                        : DateTimeFormatter.ofPattern("EEE, MMM d, uuuu h:mm a z").withZone(endedWar.zoneId())
                            .format(endedWar.truceUntil());
                    Bukkit.broadcastMessage(ChatColor.GOLD + "War #" + id + " has ended. Truce ends " + truce + ".");
                    cleanTemporaryBlocks(id);
                });
            } finally {
                busy.set(false);
            }
        }));
    }

    private void updateReminders() {
        Instant now = Instant.now();
        Set<Long> pending = new HashSet<>();
        for (War war : cache.snapshot().wars().values()) {
            if (war.effectiveState(now) != WarState.PENDING) continue;
            pending.add(war.id());
            long seconds = Math.max(0, Duration.between(now, war.scheduledStart()).getSeconds());
            EnumSet<Reminder> sent = sentReminders.computeIfAbsent(war.id(), ignored -> EnumSet.noneOf(Reminder.class));
            Reminder due = null;
            for (Reminder reminder : Reminder.values()) {
                if (seconds <= reminder.seconds && !sent.contains(reminder)
                    && (due == null || reminder.seconds < due.seconds)) due = reminder;
            }
            if (due == null) continue;
            for (Reminder reminder : Reminder.values()) if (seconds <= reminder.seconds) sent.add(reminder);
            String start = DateTimeFormatter.ofPattern("EEE, MMM d, uuuu h:mm a z")
                .withZone(war.zoneId()).format(war.scheduledStart());
            notifyParticipants(war, ChatColor.GOLD + "Campaign #" + war.id() + " begins " + due.label
                + " — " + start + ".");
        }
        sentReminders.keySet().removeIf(id -> !pending.contains(id));
    }

    private void notifyParticipants(War war, String message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Member member = cache.snapshot().member(player.getUniqueId());
            if (member != null && (member.civilizationId() == war.attackerCivilizationId()
                || member.civilizationId() == war.defenderCivilizationId())) player.sendMessage(message);
        }
    }

    private void cleanTemporaryBlocks(long warId) {
        wars.pendingCleanup(warId).whenComplete((blocks, error) -> mainThread.run(() -> {
            if (error != null || blocks == null) return;
            List<Long> cleaned = new ArrayList<>();
            for (WarService.TemporaryBlock change : blocks) {
                World world = Bukkit.getWorld(change.worldId());
                if (world == null) continue;
                var block = world.getBlockAt(change.x(), change.y(), change.z());
                if (unchanged(block.getBlockData().getAsString(), change.placedBlockData())) {
                    try {
                        if (change.originalBlockData() == null || change.originalBlockData().isBlank()) {
                            block.setType(Material.AIR, false);
                        } else {
                            block.setBlockData(Bukkit.createBlockData(change.originalBlockData()), false);
                        }
                    } catch (IllegalArgumentException invalidBlockData) {
                        plugin.getLogger().warning("Invalid original block data for campaign block #" + change.id()
                            + "; restoring air instead: " + invalidBlockData.getMessage());
                        block.setType(Material.AIR, false);
                    } catch (RuntimeException restoreFailure) {
                        plugin.getLogger().warning("Could not restore campaign block #" + change.id() + ": "
                            + restoreFailure.getMessage());
                        continue;
                    }
                }
                // Changed blocks are deliberately preserved, but still considered handled.
                cleaned.add(change.id());
            }
            wars.markCleaned(new WarService.CollectionIds(List.copyOf(cleaned))).exceptionally(markError -> null);
        }));
        wars.pendingStandardCleanup(warId).whenComplete((standards, error) -> mainThread.run(() -> {
            if (error != null || standards == null) return;
            List<Long> cleaned = new ArrayList<>();
            for (WarService.StandardCleanup standard : standards) {
                World world = Bukkit.getWorld(standard.worldId());
                if (world == null) continue;
                var block = world.getBlockAt(standard.x(), standard.y(), standard.z());
                if (items.isStandard(block)) {
                    block.setType(Material.AIR, false);
                }
                cleaned.add(standard.objectiveId());
            }
            wars.markStandardsCleaned(new WarService.CollectionIds(List.copyOf(cleaned))).exceptionally(markError -> null);
        }));
    }

    private void recoverTemporaryBlocks() {
        if (!cache.ready()) return;
        wars.campaignsNeedingCleanup().thenAccept(ids -> ids.forEach(this::cleanTemporaryBlocks))
            .exceptionally(error -> null);
    }

    static boolean unchanged(String currentBlockData, String placedBlockData) {
        return currentBlockData != null && currentBlockData.equals(placedBlockData);
    }

    @Override
    public void close() {
        if (task != null) task.cancel();
        if (cleanupTask != null) cleanupTask.cancel();
        task = null;
        cleanupTask = null;
        sentReminders.clear();
    }

    private enum Reminder {
        DAY(24 * 60 * 60, "in 24 hours"),
        HOUR(60 * 60, "in 1 hour"),
        TEN_MINUTES(10 * 60, "in 10 minutes");

        private final long seconds;
        private final String label;

        Reminder(long seconds, String label) {
            this.seconds = seconds;
            this.label = label;
        }
    }
}
