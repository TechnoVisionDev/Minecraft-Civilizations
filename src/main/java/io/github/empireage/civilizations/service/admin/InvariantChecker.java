package io.github.empireage.civilizations.service.admin;

import io.github.empireage.civilizations.config.TechnologyCatalog;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.WarState;
import io.github.empireage.civilizations.service.admin.InvariantModels.Kind;
import io.github.empireage.civilizations.service.admin.InvariantModels.Report;
import io.github.empireage.civilizations.service.admin.InvariantModels.Severity;
import io.github.empireage.civilizations.service.admin.InvariantModels.Violation;
import io.github.empireage.civilizations.util.Connectivity;
import io.github.empireage.civilizations.util.TimeUtil;
import io.github.empireage.civilizations.util.UuidBytes;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodic, read-only scan of invariants which relational constraints alone do
 * not guarantee. Checks run on the database executor and never touch Bukkit
 * worlds or entities.
 */
public final class InvariantChecker implements AutoCloseable {
    private static final Duration DEFAULT_ECONOMY_STALE_AFTER = Duration.ofMinutes(10);

    private final Database database;
    private final TechnologyCatalog technologies;
    private final Logger logger;
    private final Clock clock;
    private final Duration economyStaleAfter;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile BukkitTask scheduledTask;

    public InvariantChecker(Database database, TechnologyCatalog technologies, Logger logger) {
        this(database, technologies, logger, Clock.systemUTC(), DEFAULT_ECONOMY_STALE_AFTER);
    }

    public InvariantChecker(Database database, TechnologyCatalog technologies, Logger logger,
                            Clock clock, Duration economyStaleAfter) {
        this.database = Objects.requireNonNull(database, "database");
        this.technologies = Objects.requireNonNull(technologies, "technologies");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.economyStaleAfter = Objects.requireNonNull(economyStaleAfter, "economyStaleAfter");
        if (economyStaleAfter.isNegative() || economyStaleAfter.isZero()) {
            throw new IllegalArgumentException("economyStaleAfter must be positive");
        }
    }

    public CompletableFuture<Report> check() {
        Instant started = clock.instant();
        return database.read(connection -> inspect(connection, started, clock.instant()));
    }

    /**
     * Starts an immediate scan followed by periodic scans. Calling start again
     * safely replaces the old schedule. The consumer runs on the DB executor.
     */
    public synchronized void start(JavaPlugin plugin, Duration interval, Consumer<Report> consumer) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(consumer, "consumer");
        if (interval.isNegative() || interval.isZero()) throw new IllegalArgumentException("interval must be positive");
        stop();
        runAndLog(consumer);
        long ticks;
        try {
            ticks = Math.max(1L, Math.multiplyExact(interval.toMillis(), 20L) / 1000L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("interval is too large", overflow);
        }
        scheduledTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> runAndLog(consumer), ticks, ticks);
    }

    public synchronized void start(JavaPlugin plugin, Duration interval) {
        start(plugin, interval, ignored -> {});
    }

    public synchronized void stop() {
        BukkitTask task = scheduledTask;
        scheduledTask = null;
        if (task != null) task.cancel();
    }

    public boolean scanRunning() {
        return running.get();
    }

    private void runAndLog(Consumer<Report> consumer) {
        if (!database.healthy() || !running.compareAndSet(false, true)) return;
        check().whenComplete((report, error) -> {
            running.set(false);
            if (error != null) {
                logger.log(Level.WARNING, "Civilizations invariant scan failed: " + rootMessage(error), error);
                return;
            }
            log(report);
            try {
                consumer.accept(report);
            } catch (RuntimeException callbackFailure) {
                logger.log(Level.WARNING, "Civilizations invariant report consumer failed", callbackFailure);
            }
        });
    }

    private Report inspect(Connection connection, Instant startedAt, Instant inspectedAt) throws Exception {
        List<Violation> violations = new ArrayList<>();
        checkDuplicateMemberships(connection, violations);
        checkOrphanMemberships(connection, violations);

        Map<Long, CivilizationData> civilizations = loadCivilizations(connection);
        Map<Long, List<ClaimData>> claims = loadClaims(connection);
        Map<UUID, Long> memberships = loadMemberships(connection);
        checkClaims(civilizations, claims, memberships, violations);

        List<WarData> currentWars = loadCurrentWars(connection);
        checkWars(civilizations, currentWars, violations);
        checkResearch(connection, violations);
        checkEconomy(connection, inspectedAt, violations);
        violations.sort(Comparator.comparing(Violation::severity).reversed()
            .thenComparing(value -> value.kind().name()).thenComparing(Violation::identity));
        return new Report(startedAt, clock.instant(), violations);
    }

    private void checkDuplicateMemberships(Connection connection, List<Violation> output) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT player_uuid, COUNT(*) AS membership_count, GROUP_CONCAT(civ_id ORDER BY civ_id) AS civ_ids
            FROM civ_members GROUP BY player_uuid HAVING COUNT(*) > 1
            """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                UUID player = UuidBytes.fromBytes(result.getBytes("player_uuid"));
                output.add(violation(Kind.DUPLICATE_MEMBERSHIP, Severity.ERROR, player.toString(),
                    "Player has more than one active membership row.", Map.of(
                        "count", Long.toString(result.getLong("membership_count")),
                        "civilizations", result.getString("civ_ids"))));
            }
        }
    }

    private void checkOrphanMemberships(Connection connection, List<Violation> output) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT m.player_uuid, m.civ_id, c.status FROM civ_members m
            LEFT JOIN civilizations c ON c.id = m.civ_id
            WHERE c.id IS NULL OR c.status <> 'ACTIVE'
            """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                UUID player = UuidBytes.fromBytes(result.getBytes("player_uuid"));
                String status = result.getString("status");
                output.add(violation(Kind.ORPHAN_MEMBERSHIP, Severity.ERROR, player.toString(),
                    "Membership points to a missing or non-active civilization.", Map.of(
                        "civilizationId", Long.toString(result.getLong("civ_id")),
                        "civilizationStatus", status == null ? "MISSING" : status)));
            }
        }
    }

    private Map<Long, CivilizationData> loadCivilizations(Connection connection) throws Exception {
        Map<Long, CivilizationData> values = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, status, capital_world_uuid, capital_chunk_x, capital_chunk_z, current_war_id FROM civilizations
            """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                long id = result.getLong("id");
                long pointer = result.getLong("current_war_id");
                boolean pointerMissing = result.wasNull();
                values.put(id, new CivilizationData(id, result.getString("status"),
                    new ChunkKey(UuidBytes.fromBytes(result.getBytes("capital_world_uuid")),
                        result.getInt("capital_chunk_x"), result.getInt("capital_chunk_z")),
                    pointerMissing ? null : pointer));
            }
        }
        return values;
    }

    private Map<Long, List<ClaimData>> loadClaims(Connection connection) throws Exception {
        Map<Long, List<ClaimData>> values = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, civ_id, world_uuid, chunk_x, chunk_z, plot_type, plot_owner_uuid FROM civ_claims
            """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                long civilizationId = result.getLong("civ_id");
                ClaimData claim = new ClaimData(result.getLong("id"), civilizationId,
                    new ChunkKey(UuidBytes.fromBytes(result.getBytes("world_uuid")), result.getInt("chunk_x"), result.getInt("chunk_z")),
                    result.getString("plot_type"), UuidBytes.fromBytes(result.getBytes("plot_owner_uuid")));
                values.computeIfAbsent(civilizationId, ignored -> new ArrayList<>()).add(claim);
            }
        }
        return values;
    }

    private Map<UUID, Long> loadMemberships(Connection connection) throws Exception {
        Map<UUID, Long> values = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT player_uuid, civ_id FROM civ_members");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) values.put(UuidBytes.fromBytes(result.getBytes(1)), result.getLong(2));
        }
        return values;
    }

    static void checkClaims(Map<Long, CivilizationData> civilizations, Map<Long, List<ClaimData>> claims,
                            Map<UUID, Long> memberships, List<Violation> output) {
        for (CivilizationData civilization : civilizations.values()) {
            if (!"ACTIVE".equals(civilization.status)) continue;
            List<ClaimData> owned = claims.getOrDefault(civilization.id, List.of());
            Set<ChunkKey> keys = new LinkedHashSet<>();
            owned.forEach(claim -> keys.add(claim.key));
            if (!Connectivity.allConnected(keys, civilization.capital)) {
                output.add(violation(Kind.DISCONNECTED_CLAIMS, Severity.ERROR, Long.toString(civilization.id),
                    "Civilization claims are empty, missing the capital, cross-world, or disconnected.", Map.of(
                        "capital", civilization.capital.compact(), "claimCount", Integer.toString(owned.size()))));
            }
            for (ClaimData claim : owned) {
                boolean privatePlot = "PRIVATE".equals(claim.plotType);
                Long ownerCivilization = claim.plotOwner == null ? null : memberships.get(claim.plotOwner);
                if ((privatePlot && (claim.plotOwner == null || ownerCivilization == null || ownerCivilization != claim.civilizationId))
                    || (!privatePlot && claim.plotOwner != null)) {
                    output.add(violation(Kind.INVALID_PLOT_OWNER, Severity.ERROR, Long.toString(claim.id),
                        "Plot ownership does not match plot type and active civilization membership.", context(
                            "civilizationId", claim.civilizationId, "plotType", claim.plotType,
                            "plotOwner", claim.plotOwner, "ownerCivilizationId", ownerCivilization,
                            "chunk", claim.key.compact())));
                }
            }
        }
    }

    private List<WarData> loadCurrentWars(Connection connection) throws Exception {
        List<WarData> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, attacker_civ_id, defender_civ_id, state FROM wars
            WHERE state IN ('PENDING','ACTIVE','RESOLVING')
            """); ResultSet result = statement.executeQuery()) {
            while (result.next()) values.add(new WarData(result.getLong("id"), result.getLong("attacker_civ_id"),
                result.getLong("defender_civ_id"), WarState.valueOf(result.getString("state"))));
        }
        return values;
    }

    static void checkWars(Map<Long, CivilizationData> civilizations, List<WarData> currentWars,
                          List<Violation> output) {
        Map<Long, List<Long>> warsByCivilization = new LinkedHashMap<>();
        for (WarData war : currentWars) {
            warsByCivilization.computeIfAbsent(war.attacker, ignored -> new ArrayList<>()).add(war.id);
            warsByCivilization.computeIfAbsent(war.defender, ignored -> new ArrayList<>()).add(war.id);
        }
        warsByCivilization.forEach((civilizationId, wars) -> {
            if (wars.size() > 1) output.add(violation(Kind.MULTIPLE_CURRENT_WARS, Severity.ERROR,
                Long.toString(civilizationId), "Civilization participates in more than one current campaign.",
                Map.of("warIds", wars.toString(), "count", Integer.toString(wars.size()))));
        });
        for (CivilizationData civilization : civilizations.values()) {
            if (!"ACTIVE".equals(civilization.status)) continue;
            List<Long> actual = warsByCivilization.getOrDefault(civilization.id, List.of());
            boolean matches = actual.isEmpty() ? civilization.currentWarId == null
                : actual.size() == 1 && Objects.equals(actual.getFirst(), civilization.currentWarId);
            if (!matches) output.add(violation(Kind.WAR_POINTER_MISMATCH, Severity.ERROR,
                Long.toString(civilization.id), "Civilization current_war_id does not match current campaign rows.",
                context("pointer", civilization.currentWarId, "currentWarRows", actual)));
        }
    }

    private void checkResearch(Connection connection, List<Violation> output) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT civ_id, technology_key, 'UNLOCKED' AS source, NULL AS research_id FROM civ_technologies
            UNION ALL
            SELECT civ_id, technology_key, 'QUEUE' AS source, id AS research_id FROM research_queue
            """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String key = result.getString("technology_key");
                if (technologies.get(key) != null) continue;
                long researchId = result.getLong("research_id");
                boolean researchIdMissing = result.wasNull();
                output.add(violation(Kind.UNDEFINED_RESEARCH, Severity.ERROR,
                    result.getString("source") + ":" + (researchIdMissing ? key : researchId),
                    "Persisted research references a technology missing from technologies.yml.", context(
                        "civilizationId", result.getLong("civ_id"), "technologyKey", key,
                        "source", result.getString("source"), "researchId", researchIdMissing ? null : researchId)));
            }
        }
    }

    private void checkEconomy(Connection connection, Instant inspectedAt, List<Violation> output) throws Exception {
        Instant staleBefore = inspectedAt.minus(economyStaleAfter);
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT operation_id, operation_type, civ_id, state, retry_count, provider_response, updated_at
            FROM economy_operations
            WHERE state = 'COMPENSATION_PENDING'
               OR (state IN ('PENDING','EXTERNAL_APPLIED','DB_APPLIED','PENDING_DELIVERY',
                             'WITHDRAWAL_IN_FLIGHT','DELIVERY_IN_FLIGHT','REFUND_IN_FLIGHT')
                   AND updated_at < ?)
            ORDER BY updated_at
            """)) {
            statement.setTimestamp(1, java.sql.Timestamp.from(staleBefore));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID operation = UuidBytes.fromBytes(result.getBytes("operation_id"));
                    String state = result.getString("state");
                    boolean ambiguous = state.endsWith("_IN_FLIGHT");
                    Severity severity = "COMPENSATION_PENDING".equals(state) || ambiguous
                        ? Severity.ERROR : Severity.WARNING;
                    output.add(violation(Kind.ECONOMY_COMPENSATION, severity, operation.toString(),
                        ambiguous
                            ? "Economy provider outcome is ambiguous; reconcile the provider ledger before changing this operation."
                            : "Economy operation requires compensation/retry review.", context(
                            "operationType", result.getString("operation_type"), "civilizationId", nullableLong(result, "civ_id"),
                            "state", state, "retryCount", result.getInt("retry_count"),
                            "providerResponse", result.getString("provider_response"),
                            "updatedAt", TimeUtil.instant(result.getTimestamp("updated_at")))));
                }
            }
        }
    }

    private void log(Report report) {
        if (report.healthy()) {
            logger.info("Civilizations invariant scan passed in " + report.duration().toMillis() + " ms");
            return;
        }
        logger.warning("Civilizations invariant scan found " + report.violations().size() + " issue(s), "
            + report.errorCount() + " error(s): " + report.counts());
        for (Violation violation : report.violations()) {
            Level level = violation.severity() == Severity.ERROR ? Level.SEVERE : Level.WARNING;
            logger.log(level, "[" + violation.kind() + "] " + violation.identity() + ": " + violation.message()
                + " " + violation.context());
        }
    }

    private static Violation violation(Kind kind, Severity severity, String identity, String message,
                                       Map<String, String> context) {
        return new Violation(kind, severity, identity, message, context);
    }

    private static Map<String, String> context(Object... values) {
        LinkedHashMap<String, String> context = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            Object value = values[i + 1];
            context.put(String.valueOf(values[i]), value == null ? "<none>" : String.valueOf(value));
        }
        return Map.copyOf(context);
    }

    private static Long nullableLong(ResultSet result, String column) throws Exception {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    @Override
    public void close() {
        stop();
    }

    static record CivilizationData(long id, String status, ChunkKey capital, Long currentWarId) {}
    static record ClaimData(long id, long civilizationId, ChunkKey key, String plotType, UUID plotOwner) {}
    static record WarData(long id, long attacker, long defender, WarState state) {}
}
