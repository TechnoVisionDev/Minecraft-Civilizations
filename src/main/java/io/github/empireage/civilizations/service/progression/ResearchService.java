package io.github.empireage.civilizations.service.progression;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.config.TechnologyCatalog;
import io.github.empireage.civilizations.database.AuditLog;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.database.JsonData;
import io.github.empireage.civilizations.database.Sql;
import io.github.empireage.civilizations.domain.ResourceKey;
import io.github.empireage.civilizations.domain.Role;
import io.github.empireage.civilizations.domain.TechnologyDefinition;
import io.github.empireage.civilizations.util.UuidBytes;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Atomic research start/cancel and idempotent wall-clock completion. */
public final class ResearchService {
    private final Database database;
    private final StateCache cache;
    private final TechnologyCatalog catalog;
    private final StockpileService stockpile;
    private final Duration cancellationGrace;
    private final String serverId;

    public ResearchService(Database database, StateCache cache, TechnologyCatalog catalog,
                           StockpileService stockpile, Settings settings) {
        this.database = Objects.requireNonNull(database, "database");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.stockpile = Objects.requireNonNull(stockpile, "stockpile");
        this.cancellationGrace = settings.technology().cancellationGrace();
        this.serverId = settings.serverId();
    }

    public CompletableFuture<StartResult> start(long civilizationId, UUID actor, String technologyKey) {
        return start(civilizationId, actor, technologyKey, Instant.now());
    }

    public CompletableFuture<StartResult> start(long civilizationId, UUID actor, String technologyKey, Instant now) {
        TechnologyDefinition definition;
        try {
            definition = catalog.require(technologyKey);
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(StartResult.denied(invalid.getMessage()));
        }
        return database.transaction(connection -> {
            CivilizationBalance balance = lockCivilizationBalance(connection, civilizationId);
            String authorityFailure = researchAuthorityFailure(connection, civilizationId, actor);
            if (authorityFailure != null) return StartResult.denied(authorityFailure);
            Set<String> unlocked = unlocked(connection, civilizationId);
            if (unlocked.contains(definition.key())) return StartResult.denied("That technology is already unlocked.");
            List<String> missing = definition.prerequisites().stream().filter(key -> !unlocked.contains(key)).toList();
            if (!missing.isEmpty()) return StartResult.denied("Missing prerequisites: " + String.join(", ", missing));
            List<Integer> occupied = new ArrayList<>();
            try (var statement = Sql.prepare(connection, """
                SELECT queue_slot, technology_key FROM research_queue WHERE civ_id = ? AND state = 'ACTIVE' FOR UPDATE
                """, civilizationId); ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    occupied.add(result.getInt(1));
                    if (definition.key().equals(result.getString(2))) return StartResult.denied("That technology is already being researched.");
                }
            }
            int capacity = queueCapacity(unlocked);
            int slot = firstFreeSlot(occupied, capacity);
            if (slot < 1) return StartResult.denied("All research queues are occupied.");
            if (balance.knowledge() < definition.knowledgeCost())
                return StartResult.denied("Insufficient Knowledge: need " + definition.knowledgeCost() + ".");

            UUID operationId = UUID.randomUUID();
            if (!definition.materialCosts().isEmpty()) {
                try {
                    stockpile.applyInTransaction(connection, operationId, civilizationId, actor, definition.materialCosts(), true,
                        "RESEARCH", "technology", definition.key());
                } catch (StockpileService.InsufficientStockpileException insufficient) {
                    return StartResult.denied(insufficient.getMessage());
                }
            }
            try (var statement = Sql.prepare(connection, """
                UPDATE civilizations SET knowledge_balance = knowledge_balance - ?, row_version = row_version + 1 WHERE id = ?
                """, definition.knowledgeCost(), civilizationId)) {
                statement.executeUpdate();
            }
            int speed = modifier(unlocked, "research-speed-percent");
            Duration duration = adjustedDuration(definition.duration(), speed);
            Instant completesAt = now.plus(duration);
            long researchId;
            try (var statement = connection.prepareStatement("""
                INSERT INTO research_queue(civ_id, queue_slot, technology_key, state, started_by, started_at,
                                           completes_at, cost_snapshot, row_version)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, 0)
                """, Statement.RETURN_GENERATED_KEYS)) {
                Sql.bind(statement, civilizationId, slot, definition.key(), actor, now, completesAt,
                    JsonData.researchCost(definition.knowledgeCost(), definition.materialCosts()));
                statement.executeUpdate();
                researchId = Sql.generatedId(statement);
            }
            AuditLog.write(connection, civilizationId, actor, "research.start", "technology", definition.key(),
                Map.of("researchId", researchId, "slot", slot, "completesAt", completesAt.toString(),
                    "knowledge", definition.knowledgeCost(), "materials", serialize(definition.materialCosts())), serverId);
            return new StartResult(true, "Research started.", researchId, slot, definition.key(), completesAt);
        }).thenCompose(result -> result.success()
            ? cache.refresh().handle((ignored, refreshError) -> result)
            : CompletableFuture.completedFuture(result));
    }

    public CompletableFuture<CancelResult> cancel(long civilizationId, UUID actor, int queueSlot) {
        return cancel(civilizationId, actor, queueSlot, Instant.now());
    }

    public CompletableFuture<List<ResearchStatus>> status(long civilizationId) {
        return database.read(connection -> {
            List<ResearchStatus> status = new ArrayList<>();
            try (var statement = Sql.prepare(connection, """
                SELECT id, queue_slot, technology_key, started_by, started_at, completes_at, cost_snapshot
                FROM research_queue WHERE civ_id = ? AND state = 'ACTIVE' ORDER BY queue_slot
                """, civilizationId); ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    JsonData.ResearchCost cost = JsonData.researchCost(result.getString("cost_snapshot"));
                    status.add(new ResearchStatus(result.getLong("id"), result.getInt("queue_slot"),
                        result.getString("technology_key"), UuidBytes.fromBytes(result.getBytes("started_by")),
                        result.getTimestamp("started_at").toInstant(), result.getTimestamp("completes_at").toInstant(),
                        cost.knowledge(), cost.materials()));
                }
            }
            return List.copyOf(status);
        });
    }

    public CompletableFuture<CancelResult> cancel(long civilizationId, UUID actor, int queueSlot, Instant now) {
        return database.transaction(connection -> {
            lockCivilizationBalance(connection, civilizationId);
            String authorityFailure = researchAuthorityFailure(connection, civilizationId, actor);
            if (authorityFailure != null) return CancelResult.denied(authorityFailure);
            ActiveResearch active;
            try (var statement = Sql.prepare(connection, """
                SELECT id, technology_key, started_at, cost_snapshot FROM research_queue
                WHERE civ_id = ? AND queue_slot = ? AND state = 'ACTIVE' FOR UPDATE
                """, civilizationId, queueSlot); ResultSet result = statement.executeQuery()) {
                if (!result.next()) return CancelResult.denied("That research queue is empty.");
                active = new ActiveResearch(result.getLong("id"), result.getString("technology_key"),
                    result.getTimestamp("started_at").toInstant(), JsonData.researchCost(result.getString("cost_snapshot")));
            }
            if (!canCancel(active.startedAt(), now, cancellationGrace))
                return CancelResult.denied("The five-minute research cancellation grace period has expired.");
            UUID operationId = UUID.randomUUID();
            if (!active.cost().materials().isEmpty()) stockpile.applyInTransaction(connection, operationId, civilizationId, actor,
                active.cost().materials(), false, "RESEARCH_REFUND", "technology", active.technologyKey());
            try (var statement = Sql.prepare(connection, """
                UPDATE civilizations SET knowledge_balance = knowledge_balance + ?, row_version = row_version + 1 WHERE id = ?
                """, active.cost().knowledge(), civilizationId)) {
                statement.executeUpdate();
            }
            try (var statement = Sql.prepare(connection, "DELETE FROM research_queue WHERE id = ?", active.id())) {
                statement.executeUpdate();
            }
            AuditLog.write(connection, civilizationId, actor, "research.cancel", "technology", active.technologyKey(),
                Map.of("researchId", active.id(), "knowledgeRefund", active.cost().knowledge(),
                    "materialRefund", serialize(active.cost().materials())), serverId);
            return new CancelResult(true, "Research canceled and all costs refunded.", active.technologyKey(),
                active.cost().knowledge(), active.cost().materials());
        }).thenCompose(result -> result.success()
            ? cache.refresh().handle((ignored, refreshError) -> result)
            : CompletableFuture.completedFuture(result));
    }

    /** Completes every persisted entry whose wall-clock deadline is due, at most once. */
    public CompletableFuture<List<Completion>> completeDue(Instant now) {
        return database.read(connection -> {
            List<Long> ids = new ArrayList<>();
            try (var statement = Sql.prepare(connection, """
                SELECT id FROM research_queue WHERE state = 'ACTIVE' AND completes_at <= ? ORDER BY completes_at, id
                """, now); ResultSet result = statement.executeQuery()) {
                while (result.next()) ids.add(result.getLong(1));
            }
            return ids;
        }).thenCompose(ids -> {
            List<CompletableFuture<Completion>> futures = ids.stream().map(id -> completeOne(id, now)).toList();
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenCompose(ignored -> {
                List<Completion> completed = futures.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList();
                if (completed.isEmpty()) return CompletableFuture.completedFuture(completed);
                return cache.refresh().handle((snapshot, refreshError) -> completed);
            });
        });
    }

    private CompletableFuture<Completion> completeOne(long researchId, Instant now) {
        return database.transaction(connection -> {
            Long civilizationId;
            try (var statement = Sql.prepare(connection, "SELECT civ_id FROM research_queue WHERE id = ?", researchId);
                 ResultSet result = statement.executeQuery()) {
                civilizationId = result.next() ? result.getLong(1) : null;
            }
            if (civilizationId == null) return null;
            StockpileService.lockCivilization(connection, civilizationId);
            String technologyKey;
            UUID startedBy;
            Instant completesAt;
            try (var statement = Sql.prepare(connection, """
                SELECT technology_key, started_by, completes_at FROM research_queue
                WHERE id = ? AND civ_id = ? AND state = 'ACTIVE' FOR UPDATE
                """, researchId, civilizationId); ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                technologyKey = result.getString("technology_key");
                startedBy = UuidBytes.fromBytes(result.getBytes("started_by"));
                completesAt = result.getTimestamp("completes_at").toInstant();
            }
            if (completesAt.isAfter(now)) return null;
            int inserted;
            try (var statement = Sql.prepare(connection, """
                INSERT IGNORE INTO civ_technologies(civ_id, technology_key, unlocked_at, unlocked_by, source)
                VALUES (?, ?, ?, ?, 'RESEARCH')
                """, civilizationId, technologyKey, now, startedBy)) {
                inserted = statement.executeUpdate();
            }
            try (var statement = Sql.prepare(connection, "DELETE FROM research_queue WHERE id = ?", researchId)) {
                statement.executeUpdate();
            }
            if (inserted > 0) AuditLog.write(connection, civilizationId, startedBy, "research.complete", "technology",
                technologyKey, Map.of("researchId", researchId, "scheduledFor", completesAt.toString()), serverId);
            return new Completion(researchId, civilizationId, technologyKey, now, inserted > 0);
        });
    }

    public int queueCapacity(Set<String> unlocked) {
        return Math.max(1, 1 + modifier(unlocked, "research-queues"));
    }

    public int modifier(Set<String> unlocked, String modifierKey) {
        long total = 0;
        for (String key : unlocked) {
            TechnologyDefinition technology = catalog.get(key);
            if (technology != null) total += technology.modifiers().getOrDefault(modifierKey, 0);
        }
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, total));
    }

    public static Duration adjustedDuration(Duration base, int speedPercent) {
        if (base.isNegative() || base.isZero()) throw new IllegalArgumentException("Research duration must be positive");
        int bounded = Math.max(0, Math.min(95, speedPercent));
        long millis = base.toMillis();
        long adjusted = Math.max(1, Math.addExact(Math.multiplyExact(millis, 100L - bounded), 99L) / 100L);
        return Duration.ofMillis(adjusted);
    }

    public static boolean canCancel(Instant startedAt, Instant now, Duration grace) {
        return !now.isAfter(startedAt.plus(grace));
    }

    private CivilizationBalance lockCivilizationBalance(Connection connection, long civilizationId) throws Exception {
        try (var statement = Sql.prepare(connection, """
            SELECT knowledge_balance FROM civilizations WHERE id = ? AND status = 'ACTIVE' FOR UPDATE
            """, civilizationId); ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new IllegalStateException("Civilization is not active");
            return new CivilizationBalance(result.getLong(1));
        }
    }

    private String researchAuthorityFailure(Connection connection, long civilizationId, UUID actor) throws Exception {
        try (var statement = Sql.prepare(connection, """
            SELECT role FROM civ_members WHERE civ_id = ? AND player_uuid = ? FOR UPDATE
            """, civilizationId, actor); ResultSet result = statement.executeQuery()) {
            if (!result.next()) return "You are not a member of that civilization.";
            Role role = Role.valueOf(result.getString(1));
            if (role != Role.LEADER && role != Role.ADVISOR) return "Only leaders and advisors may manage research.";
            return null;
        }
    }

    private Set<String> unlocked(Connection connection, long civilizationId) throws Exception {
        Set<String> unlocked = new LinkedHashSet<>();
        try (var statement = Sql.prepare(connection,
            "SELECT technology_key FROM civ_technologies WHERE civ_id = ?", civilizationId);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) unlocked.add(result.getString(1));
        }
        return Set.copyOf(unlocked);
    }

    private int firstFreeSlot(List<Integer> occupied, int capacity) {
        for (int slot = 1; slot <= capacity; slot++) if (!occupied.contains(slot)) return slot;
        return -1;
    }

    private Map<String, Long> serialize(Map<ResourceKey, Long> costs) {
        Map<String, Long> values = new LinkedHashMap<>();
        costs.forEach((key, value) -> values.put(key.serialized(), value));
        return values;
    }

    private record CivilizationBalance(long knowledge) {}
    private record ActiveResearch(long id, String technologyKey, Instant startedAt, JsonData.ResearchCost cost) {}

    public record StartResult(boolean success, String message, long researchId, int slot,
                              String technologyKey, Instant completesAt) {
        public static StartResult denied(String message) {
            return new StartResult(false, message, 0, 0, null, null);
        }
    }

    public record CancelResult(boolean success, String message, String technologyKey, long knowledgeRefund,
                               Map<ResourceKey, Long> materialRefund) {
        public static CancelResult denied(String message) {
            return new CancelResult(false, message, null, 0, Map.of());
        }
    }

    public record Completion(long researchId, long civilizationId, String technologyKey, Instant completedAt,
                             boolean newlyUnlocked) {}

    public record ResearchStatus(long researchId, int slot, String technologyKey, UUID startedBy,
                                 Instant startedAt, Instant completesAt, long knowledgeCost,
                                 Map<ResourceKey, Long> materialCosts) {}
}
