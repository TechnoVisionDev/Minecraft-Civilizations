package io.github.empireage.civilizations.runtime;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.database.AuditLog;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.util.TimeUtil;
import io.github.empireage.civilizations.util.UuidBytes;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Periodically removes stale capacity credit and promotes newly established members. */
public final class EstablishmentRecalculator implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Database database;
    private final StateCache cache;
    private final Settings settings;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private BukkitTask task;

    public EstablishmentRecalculator(JavaPlugin plugin, Database database, StateCache cache, Settings settings) {
        this.plugin = plugin;
        this.database = database;
        this.cache = cache;
        this.settings = settings;
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::recalculate, 20L * 60, 20L * 3600);
    }

    public void recalculate() {
        if (!database.healthy() || !running.compareAndSet(false, true)) return;
        Instant now = Instant.now();
        LocalDate gameDate = now.atZone(settings.war().zone()).toLocalDate();
        LocalDate cutoffDate = gameDate.minusDays(Math.max(0, settings.membership().establishedWindow().toDays() - 1));
        Instant joinedBefore = now.minus(settings.membership().establishedAfter());
        Instant activeAfter = now.minus(settings.membership().establishedWindow());
        database.transaction(connection -> {
            List<Change> changes = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT m.civ_id, m.player_uuid, m.established,
                    COALESCE(SUM(CASE WHEN a.activity_date >= ? THEN a.active_seconds ELSE 0 END), 0) AS active_seconds,
                    m.joined_at, m.last_active_at
                FROM civ_members m LEFT JOIN member_activity_daily a
                    ON a.civ_id = m.civ_id AND a.player_uuid = m.player_uuid
                GROUP BY m.civ_id, m.player_uuid, m.established, m.joined_at, m.last_active_at
                """)) {
                statement.setDate(1, Date.valueOf(cutoffDate));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        long activeSeconds = result.getLong("active_seconds");
                        boolean established = !TimeUtil.instant(result.getTimestamp("joined_at")).isAfter(joinedBefore)
                            && !TimeUtil.instant(result.getTimestamp("last_active_at")).isBefore(activeAfter)
                            && activeSeconds >= settings.membership().establishedActive().toSeconds();
                        changes.add(new Change(result.getLong("civ_id"), UuidBytes.fromBytes(result.getBytes("player_uuid")),
                            result.getBoolean("established"), established, activeSeconds));
                    }
                }
            }
            try (PreparedStatement update = connection.prepareStatement("""
                UPDATE civ_members SET established = ?, eligible_playtime_seconds = ?, established_checked_at = ?
                WHERE civ_id = ? AND player_uuid = ?
                """)) {
                for (Change change : changes) {
                    update.setBoolean(1, change.after());
                    update.setLong(2, change.activeSeconds());
                    update.setTimestamp(3, Timestamp.from(now));
                    update.setLong(4, change.civilizationId());
                    update.setBytes(5, UuidBytes.toBytes(change.playerId()));
                    update.addBatch();
                    if (change.before() != change.after()) AuditLog.write(connection, change.civilizationId(), null,
                        "membership.establishment_recalculated", "player", change.playerId().toString(),
                        Map.of("before", change.before(), "after", change.after(), "activeWindowSeconds", change.activeSeconds()),
                        settings.serverId());
                }
                update.executeBatch();
            }
            return null;
        }).thenCompose(ignored -> cache.refreshAfterMutation()).whenComplete((ignored, error) -> running.set(false));
    }

    @Override
    public void close() {
        if (task != null) task.cancel();
        task = null;
    }

    private record Change(long civilizationId, UUID playerId, boolean before, boolean after, long activeSeconds) {}
}
