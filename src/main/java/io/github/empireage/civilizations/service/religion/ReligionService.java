package io.github.empireage.civilizations.service.religion;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.database.AuditLog;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.database.Sql;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.util.TimeUtil;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;

/** Restart-safe, per-player and per-god sacrifice cooldowns and favor. */
public final class ReligionService {
    public static final int MAX_FAVOR_LEVEL = 3;
    private final Database database;
    private final StateCache cache;
    private final Duration cooldown;
    private final String serverId;
    private final IntUnaryOperator rollPicker;

    public ReligionService(Database database, StateCache cache, Duration cooldown, String serverId) {
        this(database, cache, cooldown, serverId, max -> ThreadLocalRandom.current().nextInt(1, max + 1));
    }

    ReligionService(Database database, StateCache cache, Duration cooldown, String serverId,
                    IntUnaryOperator rollPicker) {
        this.database = database;
        this.cache = cache;
        this.cooldown = cooldown;
        this.serverId = serverId;
        this.rollPicker = rollPicker;
    }

    public CompletableFuture<Map<String, GodProgress>> progress(UUID playerId) {
        return database.read(connection -> {
            Map<String, GodProgress> values = new LinkedHashMap<>();
            try (PreparedStatement statement = Sql.prepare(connection, """
                SELECT c.god_key, c.available_at, COALESCE(f.favor_level, 1)
                FROM sacrifice_cooldowns c LEFT JOIN deity_favor f
                    ON f.player_uuid = c.player_uuid AND f.god_key = c.god_key
                WHERE c.player_uuid = ? ORDER BY c.god_key
                """, playerId); ResultSet result = statement.executeQuery()) {
                while (result.next()) values.put(result.getString(1),
                    new GodProgress(result.getInt(3), TimeUtil.instant(result.getTimestamp(2))));
            }
            return Map.copyOf(values);
        });
    }

    public boolean available() {
        return database.healthy();
    }

    public static int maxRoll(int favorLevel) {
        if (favorLevel < 1 || favorLevel > MAX_FAVOR_LEVEL)
            throw new IllegalArgumentException("Favor level must be between 1 and " + MAX_FAVOR_LEVEL);
        return 30 - (favorLevel - 1) * 5;
    }

    public static boolean qualifies(boolean correctOffering, int amount, int roll, int favorLevel) {
        if (roll < 1 || roll > maxRoll(favorLevel))
            throw new IllegalArgumentException("Divine roll is outside this god's favor range");
        return correctOffering && amount > roll;
    }

    public CompletableFuture<AttemptResult> record(UUID playerId, String godKey, String offering,
                                                    int amount, boolean correctOffering, Instant now) {
        UUID operationId = UUID.randomUUID();
        CompletableFuture<AttemptResult> write = database.transaction(connection -> {
            Instant available = now.plus(cooldown);
            // Establish and lock the cooldown row before reading favor or rolling. The initial
            // placeholder is never committed without the final outcome, favor and audit entry.
            try (PreparedStatement statement = Sql.prepare(connection, """
                INSERT IGNORE INTO sacrifice_cooldowns(player_uuid, god_key, operation_id, available_at,
                    last_sacrifice_at, last_offering, last_amount, last_roll, last_success)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, FALSE)
                """, playerId, godKey, operationId, now, now, offering, amount)) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = Sql.prepare(connection, """
                SELECT available_at FROM sacrifice_cooldowns
                WHERE player_uuid = ? AND god_key = ? FOR UPDATE
                """, playerId, godKey); ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("Sacrifice cooldown disappeared during update");
                Instant current = TimeUtil.instant(result.getTimestamp(1));
                if (current != null && now.isBefore(current)) return AttemptResult.cooldown(current, operationId);
            }
            try (PreparedStatement statement = Sql.prepare(connection, """
                INSERT IGNORE INTO deity_favor(player_uuid, god_key, favor_level) VALUES (?, ?, 1)
                """, playerId, godKey)) {
                statement.executeUpdate();
            }
            int level;
            try (PreparedStatement statement = Sql.prepare(connection, """
                SELECT favor_level FROM deity_favor WHERE player_uuid = ? AND god_key = ? FOR UPDATE
                """, playerId, godKey); ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("Deity favor disappeared during update");
                level = result.getInt(1);
            }
            int roll = rollPicker.applyAsInt(maxRoll(level));
            boolean success = qualifies(correctOffering, amount, roll, level);
            int newLevel = success ? Math.min(MAX_FAVOR_LEVEL, level + 1) : level;
            if (newLevel != level) try (PreparedStatement statement = Sql.prepare(connection, """
                UPDATE deity_favor SET favor_level = ? WHERE player_uuid = ? AND god_key = ?
                """, newLevel, playerId, godKey)) {
                if (statement.executeUpdate() != 1) throw new IllegalStateException("Deity favor update failed");
            }
            try (PreparedStatement statement = Sql.prepare(connection, """
                UPDATE sacrifice_cooldowns SET operation_id = ?, available_at = ?, last_sacrifice_at = ?,
                    last_offering = ?, last_amount = ?, last_roll = ?, last_success = ?
                WHERE player_uuid = ? AND god_key = ?
                """, operationId, available, now, offering, amount, roll, success, playerId, godKey)) {
                if (statement.executeUpdate() != 1) throw new IllegalStateException("Sacrifice cooldown update failed");
            }
            Member member = cache.snapshot().member(playerId);
            Long civilizationId = member == null ? null : member.civilizationId();
            AuditLog.write(connection, civilizationId, playerId, success ? "religion.sacrifice.accepted"
                : "religion.sacrifice.rejected", "god", godKey, Map.of(
                    "offering", offering, "amount", amount, "roll", roll, "maxRoll", maxRoll(level),
                    "favorBefore", level, "favorAfter", newLevel, "availableAt", available.toString()), serverId);
            return AttemptResult.recorded(available, operationId, roll, success, newLevel);
        });
        return write.handle(WriteResult::new).thenCompose(outcome -> {
            if (outcome.failure() == null) return CompletableFuture.completedFuture(outcome.result());
            return database.read(connection -> {
                try (PreparedStatement statement = Sql.prepare(connection, """
                    SELECT c.available_at, c.last_roll, c.last_success, f.favor_level
                    FROM sacrifice_cooldowns c JOIN deity_favor f
                        ON f.player_uuid = c.player_uuid AND f.god_key = c.god_key
                    WHERE c.operation_id = ?
                    """, operationId); ResultSet result = statement.executeQuery()) {
                    return result.next() ? AttemptResult.recorded(TimeUtil.instant(result.getTimestamp(1)),
                        operationId, result.getInt(2), result.getBoolean(3), result.getInt(4)) : null;
                }
            }).handle((committed, lookupFailure) -> {
                if (lookupFailure != null) return AttemptResult.uncertain(operationId, outcome.failure());
                if (committed != null) return committed;
                return AttemptResult.rolledBack(operationId, outcome.failure());
            });
        });
    }

    public record GodProgress(int favorLevel, Instant availableAt) {
        public GodProgress { maxRoll(favorLevel); }
    }

    public record AttemptResult(boolean recorded, boolean safeToRestore, Instant availableAt,
                                UUID operationId, Throwable failure, int roll, boolean success, int favorLevel) {
        static AttemptResult recorded(Instant availableAt, UUID operationId, int roll, boolean success, int level) {
            return new AttemptResult(true, false, availableAt, operationId, null, roll, success, level);
        }

        static AttemptResult cooldown(Instant availableAt, UUID operationId) {
            return new AttemptResult(false, true, availableAt, operationId, null, 0, false, 0);
        }

        static AttemptResult rolledBack(UUID operationId, Throwable failure) {
            return new AttemptResult(false, true, null, operationId, failure, 0, false, 0);
        }

        static AttemptResult uncertain(UUID operationId, Throwable failure) {
            return new AttemptResult(false, false, null, operationId, failure, 0, false, 0);
        }
    }

    private record WriteResult(AttemptResult result, Throwable failure) {}
}
