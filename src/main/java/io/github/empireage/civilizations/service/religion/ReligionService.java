package io.github.empireage.civilizations.service.religion;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.database.AuditLog;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.util.TimeUtil;
import io.github.empireage.civilizations.util.UuidBytes;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Restart-safe, per-player and per-god sacrifice cooldowns. */
public final class ReligionService {
    private final Database database;
    private final StateCache cache;
    private final Duration cooldown;
    private final String serverId;

    public ReligionService(Database database, StateCache cache, Duration cooldown, String serverId) {
        this.database = database;
        this.cache = cache;
        this.cooldown = cooldown;
        this.serverId = serverId;
    }

    public CompletableFuture<Map<String, Instant>> cooldowns(UUID playerId, Instant now) {
        return database.read(connection -> {
            Map<String, Instant> values = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT god_key, available_at FROM sacrifice_cooldowns
                WHERE player_uuid = ? AND available_at > ? ORDER BY god_key
                """)) {
                statement.setBytes(1, UuidBytes.toBytes(playerId));
                statement.setTimestamp(2, Timestamp.from(now));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) values.put(result.getString(1), TimeUtil.instant(result.getTimestamp(2)));
                }
            }
            return Map.copyOf(values);
        });
    }

    public boolean available() {
        return database.healthy();
    }

    public CompletableFuture<AttemptResult> record(UUID playerId, String godKey, String offering,
                                                    int amount, int roll, boolean success, Instant now) {
        UUID operationId = UUID.randomUUID();
        CompletableFuture<AttemptResult> write = database.transaction(connection -> {
            Instant available = now.plus(cooldown);
            boolean inserted;
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT IGNORE INTO sacrifice_cooldowns(player_uuid, god_key, operation_id, available_at,
                    last_sacrifice_at, last_offering, last_amount, last_roll, last_success)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                statement.setBytes(1, UuidBytes.toBytes(playerId));
                statement.setString(2, godKey);
                statement.setBytes(3, UuidBytes.toBytes(operationId));
                statement.setTimestamp(4, Timestamp.from(available));
                statement.setTimestamp(5, Timestamp.from(now));
                statement.setString(6, offering);
                statement.setInt(7, amount);
                statement.setInt(8, roll);
                statement.setBoolean(9, success);
                inserted = statement.executeUpdate() == 1;
            }
            if (!inserted) try (PreparedStatement statement = connection.prepareStatement("""
                SELECT available_at FROM sacrifice_cooldowns
                WHERE player_uuid = ? AND god_key = ? FOR UPDATE
                """)) {
                statement.setBytes(1, UuidBytes.toBytes(playerId));
                statement.setString(2, godKey);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new IllegalStateException("Sacrifice cooldown disappeared during update");
                    Instant current = TimeUtil.instant(result.getTimestamp(1));
                    if (current != null && now.isBefore(current)) return AttemptResult.cooldown(current, operationId);
                }
            }
            if (!inserted) try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE sacrifice_cooldowns SET operation_id = ?, available_at = ?, last_sacrifice_at = ?,
                    last_offering = ?, last_amount = ?, last_roll = ?, last_success = ?
                WHERE player_uuid = ? AND god_key = ?
                """)) {
                statement.setBytes(1, UuidBytes.toBytes(operationId));
                statement.setTimestamp(2, Timestamp.from(available));
                statement.setTimestamp(3, Timestamp.from(now));
                statement.setString(4, offering);
                statement.setInt(5, amount);
                statement.setInt(6, roll);
                statement.setBoolean(7, success);
                statement.setBytes(8, UuidBytes.toBytes(playerId));
                statement.setString(9, godKey);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("Sacrifice cooldown update failed");
            }
            Member member = cache.snapshot().member(playerId);
            Long civilizationId = member == null ? null : member.civilizationId();
            AuditLog.write(connection, civilizationId, playerId, success ? "religion.sacrifice.accepted"
                : "religion.sacrifice.rejected", "god", godKey, Map.of(
                    "offering", offering, "amount", amount, "roll", roll,
                    "availableAt", available.toString()), serverId);
            return AttemptResult.recorded(available, operationId);
        });
        return write.handle(WriteResult::new).thenCompose(outcome -> {
            if (outcome.failure() == null) return CompletableFuture.completedFuture(outcome.result());
            return database.read(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT available_at FROM sacrifice_cooldowns WHERE operation_id = ?")) {
                    statement.setBytes(1, UuidBytes.toBytes(operationId));
                    try (ResultSet result = statement.executeQuery()) {
                        return result.next() ? TimeUtil.instant(result.getTimestamp(1)) : null;
                    }
                }
            }).handle((committedAt, lookupFailure) -> {
                if (lookupFailure != null) return AttemptResult.uncertain(operationId, outcome.failure());
                if (committedAt != null) return AttemptResult.recorded(committedAt, operationId);
                return AttemptResult.rolledBack(operationId, outcome.failure());
            });
        });
    }

    public record AttemptResult(boolean recorded, boolean safeToRestore, Instant availableAt,
                                UUID operationId, Throwable failure) {
        static AttemptResult recorded(Instant availableAt, UUID operationId) {
            return new AttemptResult(true, false, availableAt, operationId, null);
        }

        static AttemptResult cooldown(Instant availableAt, UUID operationId) {
            return new AttemptResult(false, true, availableAt, operationId, null);
        }

        static AttemptResult rolledBack(UUID operationId, Throwable failure) {
            return new AttemptResult(false, true, null, operationId, failure);
        }

        static AttemptResult uncertain(UUID operationId, Throwable failure) {
            return new AttemptResult(false, false, null, operationId, failure);
        }
    }

    private record WriteResult(AttemptResult result, Throwable failure) {}
}
