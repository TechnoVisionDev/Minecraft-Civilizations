package io.github.empireage.civilizations.service.progression;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.config.ResourceCatalog;
import io.github.empireage.civilizations.config.ResourceText;
import io.github.empireage.civilizations.database.AuditLog;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.database.Sql;
import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.domain.ResourceKey;
import io.github.empireage.civilizations.util.UuidBytes;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Transactional civic stockpile balances and immutable ledger history. */
public final class StockpileService {
    private final Database database;
    private final StateCache cache;
    private final String serverId;
    private final ResourceCatalog resources;

    public StockpileService(Database database, StateCache cache, String serverId) {
        this(database, cache, serverId, null);
    }

    public StockpileService(Database database, StateCache cache, String serverId, ResourceCatalog resources) {
        this.database = Objects.requireNonNull(database, "database");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.resources = resources;
    }

    public CompletableFuture<Map<ResourceKey, Long>> balances(long civilizationId) {
        return database.read(connection -> {
            Map<ResourceKey, Long> balances = new LinkedHashMap<>();
            try (var statement = Sql.prepare(connection, """
                SELECT resource_key, tier, quantity FROM civ_stockpile
                WHERE civ_id = ? ORDER BY resource_key, tier
                """, civilizationId); ResultSet result = statement.executeQuery()) {
                while (result.next()) balances.put(
                    new ResourceKey(result.getString("resource_key"), result.getInt("tier")), result.getLong("quantity"));
            }
            return Map.copyOf(balances);
        });
    }

    public CompletableFuture<List<LedgerEntry>> history(long civilizationId, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        return database.read(connection -> {
            List<LedgerEntry> entries = new ArrayList<>();
            try (var statement = Sql.prepare(connection, """
                SELECT id, operation_id, actor_uuid, resource_key, tier, delta, reason,
                       related_type, related_id, created_at
                FROM stockpile_ledger WHERE civ_id = ? ORDER BY id DESC LIMIT ? OFFSET ?
                """, civilizationId, safeSize, (safePage - 1) * safeSize); ResultSet result = statement.executeQuery()) {
                while (result.next()) entries.add(new LedgerEntry(
                    result.getLong("id"), UuidBytes.fromBytes(result.getBytes("operation_id")),
                    UuidBytes.fromBytes(result.getBytes("actor_uuid")),
                    new ResourceKey(result.getString("resource_key"), result.getInt("tier")),
                    result.getLong("delta"), result.getString("reason"), result.getString("related_type"),
                    result.getString("related_id"), result.getTimestamp("created_at").toInstant()));
            }
            return List.copyOf(entries);
        });
    }

    public CompletableFuture<OperationResult> credit(long civilizationId, UUID actor, Map<ResourceKey, Long> amounts,
                                                       String reason, String relatedType, String relatedId) {
        UUID operationId = UUID.randomUUID();
        return database.transaction(connection -> {
            lockCivilization(connection, civilizationId);
            applyInTransaction(connection, operationId, civilizationId, actor, amounts, false, reason, relatedType, relatedId);
            AuditLog.write(connection, civilizationId, actor, "stockpile." + reason.toLowerCase(), relatedType,
                relatedId, Map.of("operation", operationId.toString(), "resources", serialize(amounts)), serverId);
            return OperationResult.ok("Stockpile credited.");
        }).thenCompose(result -> cache.refresh().handle((ignored, refreshError) -> result));
    }

    public CompletableFuture<OperationResult> spend(long civilizationId, UUID actor, Map<ResourceKey, Long> amounts,
                                                      String reason, String relatedType, String relatedId) {
        UUID operationId = UUID.randomUUID();
        return database.transaction(connection -> {
            lockCivilization(connection, civilizationId);
            applyInTransaction(connection, operationId, civilizationId, actor, amounts, true, reason, relatedType, relatedId);
            AuditLog.write(connection, civilizationId, actor, "stockpile." + reason.toLowerCase(), relatedType,
                relatedId, Map.of("operation", operationId.toString(), "resources", serialize(amounts)), serverId);
            return OperationResult.ok("Stockpile resources spent.");
        }).thenCompose(result -> cache.refresh().handle((ignored, refreshError) -> result));
    }

    /** Caller must lock the civilization row before invoking this method. */
    public void applyInTransaction(Connection connection, UUID operationId, long civilizationId, UUID actor,
                                   Map<ResourceKey, Long> amounts, boolean debit, String reason,
                                   String relatedType, String relatedId) throws Exception {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(amounts, "amounts");
        if (reason == null || reason.isBlank() || reason.length() > 32) throw new IllegalArgumentException("Invalid ledger reason");
        Map<ResourceKey, Long> normalized = normalize(amounts);
        Map<ResourceKey, Long> before = new LinkedHashMap<>();
        for (ResourceKey key : normalized.keySet()) before.put(key, lockedBalance(connection, civilizationId, key));
        if (debit) for (Map.Entry<ResourceKey, Long> entry : normalized.entrySet()) {
            if (before.get(entry.getKey()) < entry.getValue()) throw new InsufficientStockpileException(
                entry.getKey(), entry.getValue(), before.get(entry.getKey()), resources);
        }
        Instant now = Instant.now();
        for (Map.Entry<ResourceKey, Long> entry : normalized.entrySet()) {
            ResourceKey key = entry.getKey();
            long amount = entry.getValue();
            long resulting = debit ? before.get(key) - amount : Math.addExact(before.get(key), amount);
            if (before.get(key) == 0L && !debit) {
                try (var statement = Sql.prepare(connection, """
                    INSERT INTO civ_stockpile(civ_id, resource_key, tier, quantity) VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE quantity = VALUES(quantity)
                    """, civilizationId, key.family(), key.tier(), resulting)) {
                    statement.executeUpdate();
                }
            } else {
                try (var statement = Sql.prepare(connection, """
                    UPDATE civ_stockpile SET quantity = ? WHERE civ_id = ? AND resource_key = ? AND tier = ?
                    """, resulting, civilizationId, key.family(), key.tier())) {
                    statement.executeUpdate();
                }
            }
            try (var statement = Sql.prepare(connection, """
                INSERT INTO stockpile_ledger(operation_id, civ_id, actor_uuid, resource_key, tier, delta,
                                             reason, related_type, related_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, operationId, civilizationId, actor, key.family(), key.tier(), debit ? -amount : amount,
                reason, relatedType, relatedId, now)) {
                statement.executeUpdate();
            }
        }
    }

    public static void lockCivilization(Connection connection, long civilizationId) throws Exception {
        try (var statement = Sql.prepare(connection,
            "SELECT id FROM civilizations WHERE id = ? AND status = 'ACTIVE' FOR UPDATE", civilizationId);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new IllegalStateException("Civilization is not active");
        }
    }

    private long lockedBalance(Connection connection, long civilizationId, ResourceKey key) throws Exception {
        try (var statement = Sql.prepare(connection, """
            SELECT quantity FROM civ_stockpile
            WHERE civ_id = ? AND resource_key = ? AND tier = ? FOR UPDATE
            """, civilizationId, key.family(), key.tier()); ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }

    private Map<ResourceKey, Long> normalize(Map<ResourceKey, Long> amounts) {
        Map<ResourceKey, Long> normalized = new LinkedHashMap<>();
        amounts.forEach((key, value) -> {
            Objects.requireNonNull(key, "resource key");
            if (value == null || value <= 0) throw new IllegalArgumentException("Resource amounts must be positive");
            normalized.merge(key, value, Math::addExact);
        });
        if (normalized.isEmpty()) throw new IllegalArgumentException("At least one resource amount is required");
        return normalized;
    }

    private Map<String, Long> serialize(Map<ResourceKey, Long> values) {
        Map<String, Long> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key.serialized(), value));
        return result;
    }

    public record LedgerEntry(long id, UUID operationId, UUID actor, ResourceKey resource, long delta,
                              String reason, String relatedType, String relatedId, Instant createdAt) {}

    public static final class InsufficientStockpileException extends IllegalStateException {
        public InsufficientStockpileException(ResourceKey key, long required, long available) {
            this(key, required, available, null);
        }

        public InsufficientStockpileException(ResourceKey key, long required, long available, ResourceCatalog resources) {
            super("Insufficient " + ResourceText.name(resources, key) + ": need " + required + ", have " + available);
        }
    }
}
