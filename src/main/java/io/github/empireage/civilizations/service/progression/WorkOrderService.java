package io.github.empireage.civilizations.service.progression;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.config.WorkOrderCatalog;
import io.github.empireage.civilizations.database.AuditLog;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.database.Sql;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.ResourceKey;
import org.bukkit.Material;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Persistent, civilization-wide Basic/Industrial/Strategic supply contracts. */
public final class WorkOrderService {
    private static final Gson GSON = new Gson();

    private final Database database;
    private final StateCache cache;
    private final WorkOrderCatalog catalog;
    private final StockpileService stockpile;
    private final String serverId;

    public WorkOrderService(Database database, StateCache cache, WorkOrderCatalog catalog,
                            StockpileService stockpile, String serverId) {
        this.database = Objects.requireNonNull(database, "database");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.stockpile = Objects.requireNonNull(stockpile, "stockpile");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
    }

    public CompletableFuture<List<WorkOrderView>> ensureOrders(long civilizationId, Instant now) {
        return database.transaction(connection -> {
            StockpileService.lockCivilization(connection, civilizationId);
            ensureOrdersInTransaction(connection, civilizationId, now);
            return readInTransaction(connection, civilizationId, null);
        });
    }

    public CompletableFuture<Void> ensureAllOrders(Instant now) {
        CompletableFuture<?>[] futures = cache.snapshot().civilizations().keySet().stream()
            .map(id -> ensureOrders(id, now).exceptionally(error -> List.of()))
            .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    public CompletableFuture<List<WorkOrderView>> listForPlayer(long civilizationId, UUID playerId, Instant now) {
        return ensureOrders(civilizationId, now).thenCompose(ignored -> database.read(connection ->
            readInTransaction(connection, civilizationId, playerId)));
    }

    /** Deliberately spends a complete order from the shared stockpile. */
    public CompletableFuture<SubmissionResult> complete(UUID actorId, long orderId, Instant now) {
        Objects.requireNonNull(actorId, "actorId");
        Member cached = cache.snapshot().member(actorId);
        if (cached == null) return CompletableFuture.completedFuture(
            SubmissionResult.denied("You do not belong to a civilization."));
        long civilizationId = cached.civilizationId();
        return database.transaction(connection -> {
            StockpileService.lockCivilization(connection, civilizationId);
            if (!mayComplete(connection, civilizationId, actorId)) return SubmissionResult.denied(
                "Only leaders and advisors may complete work orders from the shared stockpile.");
            ensureOrdersInTransaction(connection, civilizationId, now);
            String key;
            WorkOrderCatalog.Category category;
            long target;
            int reward;
            Instant cooldown;
            try (var statement = Sql.prepare(connection, """
                SELECT order_key, category, target, reward, cooldown_until
                FROM work_orders WHERE id = ? AND civ_id = ? AND status = 'ACTIVE' FOR UPDATE
                """, orderId, civilizationId); ResultSet order = statement.executeQuery()) {
                if (!order.next()) return SubmissionResult.denied("That work order is no longer active.");
                key = order.getString("order_key");
                category = WorkOrderCatalog.Category.valueOf(order.getString("category"));
                target = order.getLong("target");
                reward = order.getInt("reward");
                cooldown = order.getTimestamp("cooldown_until") == null ? null
                    : order.getTimestamp("cooldown_until").toInstant();
            }
            if (cooldown != null && now.isBefore(cooldown)) {
                return SubmissionResult.denied("This category is still cooling down.");
            }
            WorkOrderCatalog.Template template = catalog.require(key);
            if (!template.civic()) return SubmissionResult.denied(
                "This legacy work order is being replaced; reopen the work-order menu.");
            try {
                stockpile.applyInTransaction(connection, UUID.randomUUID(), civilizationId, actorId,
                    Map.of(template.civicResource(), target), true, "WORK_ORDER", "work_order", Long.toString(orderId));
            } catch (StockpileService.InsufficientStockpileException insufficient) {
                return SubmissionResult.denied(insufficient.getMessage());
            }
            try (var statement = Sql.prepare(connection, """
                UPDATE work_orders SET progress = ?, status = 'COMPLETED', completed_at = ?, completed_by = ?
                WHERE id = ? AND status = 'ACTIVE'
                """, target, now, actorId, orderId)) {
                if (statement.executeUpdate() != 1) throw new IllegalStateException(
                    "Work order changed while spending its stockpile requirement");
            }
            try (var statement = Sql.prepare(connection, """
                UPDATE civilizations SET knowledge_balance = knowledge_balance + ?, row_version = row_version + 1 WHERE id = ?
                """, reward, civilizationId)) {
                statement.executeUpdate();
            }
            String replacement = insertReplacement(connection, civilizationId, category,
                unlockedTechnologies(connection, civilizationId), now, now.plus(catalog.cooldown()));
            AuditLog.write(connection, civilizationId, actorId, "work_order.complete", "work_order",
                Long.toString(orderId), Map.of("order", key, "category", category.name(), "reward", reward,
                    "resource", template.civicResource().serialized(), "spent", target,
                    "replacement", replacement), serverId);
            return new SubmissionResult(true, "Completed " + template.name()
                + " from the civilization stockpile and earned " + reward + " Knowledge.",
                target, target, target, true, reward, replacement);
        }).thenCompose(result -> result.success()
            ? cache.refresh().handle((ignored, refreshFailure) -> result)
            : CompletableFuture.completedFuture(result));
    }

    public void ensureOrdersInTransaction(Connection connection, long civilizationId, Instant now) throws Exception {
        Set<String> unlocked = unlockedTechnologies(connection, civilizationId);
        for (WorkOrderCatalog.Category category : WorkOrderCatalog.Category.values()) {
            List<Long> invalidOrDuplicate = new ArrayList<>();
            boolean retained = false;
            try (var statement = Sql.prepare(connection, """
                SELECT id, order_key, target, reward, config_snapshot FROM work_orders
                WHERE civ_id = ? AND category = ? AND status = 'ACTIVE' ORDER BY id FOR UPDATE
                """, civilizationId, category); ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String key = result.getString("order_key");
                    WorkOrderCatalog.Template template = catalog.templates().get(key);
                    boolean current = template != null && template.category() == category
                        && result.getLong("target") == template.target() && result.getInt("reward") == template.reward();
                    if (current) {
                        try {
                            Snapshot snapshot = parseSnapshot(result.getString("config_snapshot"));
                            current = snapshot.materials().equals(template.materials())
                                && Objects.equals(snapshot.civicResource(), template.civicResource());
                        } catch (RuntimeException malformed) {
                            current = false;
                        }
                    }
                    if (!retained && current) retained = true;
                    else invalidOrDuplicate.add(result.getLong("id"));
                }
            }
            for (long orderId : invalidOrDuplicate) {
                try (var statement = Sql.prepare(connection, """
                    UPDATE work_orders SET status = 'CANCELLED', completed_at = ?
                    WHERE id = ? AND status = 'ACTIVE'
                    """, now, orderId)) {
                    statement.executeUpdate();
                }
            }
            if (!retained) insertReplacement(connection, civilizationId, category, unlocked, now, null);
        }
    }

    private String insertReplacement(Connection connection, long civilizationId, WorkOrderCatalog.Category category,
                                     Set<String> unlocked, Instant generatedAt, Instant cooldownUntil) throws Exception {
        List<String> recent = new ArrayList<>();
        try (var statement = Sql.prepare(connection, """
            SELECT order_key FROM work_orders WHERE civ_id = ? AND category = ? AND status = 'COMPLETED'
            ORDER BY completed_at DESC, id DESC LIMIT 2
            """, civilizationId, category); ResultSet result = statement.executeQuery()) {
            while (result.next()) recent.add(result.getString(1));
        }
        long seed = civilizationId ^ generatedAt.toEpochMilli() ^ ((long) category.ordinal() << 48);
        WorkOrderCatalog.Template selected = WorkOrderSchedule.select(catalog, category, unlocked, recent, seed);
        try (var statement = Sql.prepare(connection, """
            INSERT INTO work_orders(civ_id, category, order_key, target, progress, status, reward,
                                    config_snapshot, generated_at, cooldown_until)
            VALUES (?, ?, ?, ?, 0, 'ACTIVE', ?, ?, ?, ?)
            """, civilizationId, category, selected.key(), selected.target(), selected.reward(), snapshot(selected),
            generatedAt, cooldownUntil)) {
            statement.executeUpdate();
        }
        return selected.key();
    }

    private List<WorkOrderView> readInTransaction(Connection connection, long civilizationId, UUID playerId) throws Exception {
        List<WorkOrderView> views = new ArrayList<>();
        String query = """
            SELECT o.id, o.category, o.order_key, o.target, o.reward, o.generated_at, o.cooldown_until
            FROM work_orders o
            WHERE o.civ_id = ? AND o.status = 'ACTIVE' ORDER BY FIELD(o.category, 'BASIC', 'INDUSTRIAL', 'STRATEGIC')
            """;
        try (var statement = Sql.prepare(connection, query, civilizationId); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                WorkOrderCatalog.Template template = catalog.require(result.getString("order_key"));
                long balance = template.civic() ? stockpileBalance(connection, civilizationId, template.civicResource()) : 0L;
                long progress = Math.min(balance, result.getLong("target"));
                views.add(new WorkOrderView(result.getLong("id"), WorkOrderCatalog.Category.valueOf(result.getString("category")),
                    template.key(), template.name(), template.materials(), template.civicResource(), result.getLong("target"),
                    progress, result.getInt("reward"), balance,
                    result.getTimestamp("generated_at").toInstant(), result.getTimestamp("cooldown_until") == null ? null
                    : result.getTimestamp("cooldown_until").toInstant()));
            }
        }
        return List.copyOf(views);
    }

    private boolean mayComplete(Connection connection, long civilizationId, UUID actorId) throws Exception {
        try (var statement = Sql.prepare(connection,
            "SELECT role FROM civ_members WHERE civ_id = ? AND player_uuid = ? FOR UPDATE",
            civilizationId, actorId); ResultSet result = statement.executeQuery()) {
            if (!result.next()) return false;
            String role = result.getString(1);
            return "LEADER".equals(role) || "ADVISOR".equals(role);
        }
    }

    private long stockpileBalance(Connection connection, long civilizationId, ResourceKey key) throws Exception {
        try (var statement = Sql.prepare(connection, """
            SELECT quantity FROM civ_stockpile WHERE civ_id = ? AND resource_key = ? AND tier = ?
            """, civilizationId, key.family(), key.tier()); ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }

    private Set<String> unlockedTechnologies(Connection connection, long civilizationId) throws Exception {
        Set<String> unlocked = new LinkedHashSet<>();
        try (var statement = Sql.prepare(connection, "SELECT technology_key FROM civ_technologies WHERE civ_id = ?", civilizationId);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) unlocked.add(result.getString(1));
        }
        return Set.copyOf(unlocked);
    }

    private String snapshot(WorkOrderCatalog.Template template) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("version", 1);
        values.put("name", template.name());
        values.put("materials", template.materials().stream().map(Material::name).sorted().toList());
        values.put("civicResource", template.civicResource() == null ? null : template.civicResource().serialized());
        return GSON.toJson(values);
    }

    private Snapshot parseSnapshot(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Set<Material> materials = new LinkedHashSet<>();
        if (root.has("materials")) root.getAsJsonArray("materials").forEach(value -> {
            Material material = Material.matchMaterial(value.getAsString());
            if (material != null) materials.add(material);
        });
        ResourceKey civic = root.has("civicResource") && !root.get("civicResource").isJsonNull()
            ? ResourceKey.parse(root.get("civicResource").getAsString()) : null;
        return new Snapshot(Set.copyOf(materials), civic);
    }

    private record Snapshot(Set<Material> materials, ResourceKey civicResource) {}

    public record WorkOrderView(long id, WorkOrderCatalog.Category category, String key, String name,
                                Set<Material> materials, ResourceKey civicResource, long target, long progress,
                                int reward, long stockpileBalance,
                                Instant generatedAt, Instant cooldownUntil) {
        public long remaining() { return Math.max(0, target - progress); }
        public boolean ready() { return stockpileBalance >= target; }
    }

    public record SubmissionResult(boolean success, String message, long credited, long progress, long target,
                                   boolean completed, int knowledgeAwarded, String replacementKey) {
        static SubmissionResult denied(String message) {
            return new SubmissionResult(false, message, 0, 0, 0, false, 0, null);
        }
    }
}
