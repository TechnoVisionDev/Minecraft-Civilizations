package io.github.empireage.civilizations.service.admin;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.concurrent.CivilizationLocks;
import io.github.empireage.civilizations.config.ResourceCatalog;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.config.TechnologyCatalog;
import io.github.empireage.civilizations.database.AuditLog;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.database.JsonData;
import io.github.empireage.civilizations.database.Sql;
import io.github.empireage.civilizations.domain.Claim;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.Civilization;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.ResourceKey;
import io.github.empireage.civilizations.domain.Role;
import io.github.empireage.civilizations.domain.TechnologyDefinition;
import io.github.empireage.civilizations.domain.War;
import io.github.empireage.civilizations.domain.WarState;
import io.github.empireage.civilizations.service.admin.AdminModels.AuditEntry;
import io.github.empireage.civilizations.service.admin.AdminModels.AuditFilter;
import io.github.empireage.civilizations.service.admin.AdminModels.ForcePreview;
import io.github.empireage.civilizations.service.admin.AdminModels.Inspection;
import io.github.empireage.civilizations.service.admin.AdminModels.Migration;
import io.github.empireage.civilizations.service.admin.AdminModels.Result;
import io.github.empireage.civilizations.service.admin.AdminModels.SchemaReport;
import io.github.empireage.civilizations.service.territory.TerritoryRules;
import io.github.empireage.civilizations.util.Connectivity;
import io.github.empireage.civilizations.util.NameNormalizer;
import io.github.empireage.civilizations.util.TimeUtil;
import io.github.empireage.civilizations.util.UuidBytes;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous, auditable repair and diagnostics facade for the admin command
 * tree. Permission checks intentionally remain in the command layer: keeping
 * this class independent of a Bukkit sender also makes console and automation
 * callers behave identically.
 */
public final class AdminService {
    private static final int EXPECTED_SCHEMA_VERSION = 5;
    private static final Map<Integer, String> PACKAGED_MIGRATIONS = Map.of(
        1, "db/migration/V1__initial_schema.sql",
        2, "db/migration/V2__persistent_progression.sql",
        3, "db/migration/V3__civilization_names_only.sql",
        4, "db/migration/V4__final_progression_catalog_cleanup.sql",
        5, "db/migration/V5__religion_sacrifices.sql",
        6, "db/migration/V6__weekly_plot_taxes.sql");
    private static final Set<String> REQUIRED_TABLES = Set.of(
        "schema_history", "civilizations", "civ_coordination_locks", "civ_members", "player_membership_history",
        "member_activity_daily", "civ_invites", "civ_claims", "plot_trust", "civ_stockpile",
        "stockpile_ledger", "player_advancement_credits", "civ_advancement_credits", "civ_technologies", "research_queue",
        "daily_work_orders", "work_order_contributions", "work_orders", "persistent_work_order_contributions",
        "wars", "war_roster", "war_objectives",
        "war_block_changes", "economy_operations", "treasury_ledger", "civ_audit_log",
        "player_settings", "civ_milestones", "sacrifice_cooldowns",
        "civ_plot_taxes", "plot_tax_accounts", "plot_tax_bills"
    );

    private final Database database;
    private final StateCache cache;
    private final CivilizationLocks locks;
    private final Settings settings;
    private final ResourceCatalog resources;
    private final TechnologyCatalog technologies;
    private final Clock clock;

    public AdminService(Database database, StateCache cache, CivilizationLocks locks, Settings settings,
                        ResourceCatalog resources, TechnologyCatalog technologies) {
        this(database, cache, locks, settings, resources, technologies, Clock.systemUTC());
    }

    public AdminService(Database database, StateCache cache, CivilizationLocks locks, Settings settings,
                        ResourceCatalog resources, TechnologyCatalog technologies, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.technologies = Objects.requireNonNull(technologies, "technologies");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CompletableFuture<Inspection> inspectCivilization(String nameTagOrId) {
        String input = requireText(nameTagOrId, "civilization");
        StateSnapshot snapshot = cache.snapshot();
        Civilization cached = cachedCivilization(snapshot, input);
        return database.read(connection -> {
            Map<String, String> persisted = new LinkedHashMap<>();
            Long persistedId = loadCivilizationInspection(connection, input, persisted);
            Map<String, String> cachedValues = civilizationValues(snapshot, cached);
            List<String> warnings = identityWarnings("civilization", cached == null ? null : cached.id(), persistedId,
                snapshot.loadedAt());
            return new Inspection("civilization", input, clock.instant(), cachedValues, persisted, warnings);
        });
    }

    public CompletableFuture<Inspection> inspectClaim(ChunkKey key) {
        Objects.requireNonNull(key, "key");
        StateSnapshot snapshot = cache.snapshot();
        Claim cached = snapshot.claim(key);
        return database.read(connection -> {
            Map<String, String> persisted = new LinkedHashMap<>();
            Long persistedId = loadClaimInspection(connection, key, persisted);
            Map<String, String> cachedValues = claimValues(snapshot, cached);
            List<String> warnings = identityWarnings("claim", cached == null ? null : cached.id(), persistedId,
                snapshot.loadedAt());
            return new Inspection("claim", key.compact(), clock.instant(), cachedValues, persisted, warnings);
        });
    }

    public CompletableFuture<Inspection> inspectPlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        StateSnapshot snapshot = cache.snapshot();
        Member cached = snapshot.member(playerId);
        return database.read(connection -> {
            Map<String, String> persisted = new LinkedHashMap<>();
            Long persistedCivilization = loadPlayerInspection(connection, playerId, persisted);
            Map<String, String> cachedValues = memberValues(snapshot, cached);
            List<String> warnings = identityWarnings("player membership",
                cached == null ? null : cached.civilizationId(), persistedCivilization, snapshot.loadedAt());
            return new Inspection("player", playerId.toString(), clock.instant(), cachedValues, persisted, warnings);
        });
    }

    public CompletableFuture<Inspection> inspectWar(long warId) {
        if (warId <= 0) throw new IllegalArgumentException("warId must be positive");
        StateSnapshot snapshot = cache.snapshot();
        War cached = snapshot.wars().get(warId);
        return database.read(connection -> {
            Map<String, String> persisted = new LinkedHashMap<>();
            Long persistedId = loadWarInspection(connection, warId, persisted);
            Map<String, String> cachedValues = warValues(snapshot, cached);
            List<String> warnings = identityWarnings("war", cached == null ? null : cached.id(), persistedId,
                snapshot.loadedAt());
            return new Inspection("war", Long.toString(warId), clock.instant(), cachedValues, persisted, warnings);
        });
    }

    /** Preview uses the immutable cache and never mutates state. */
    public ForcePreview previewForceClaim(String civilizationName, ChunkKey key) {
        Objects.requireNonNull(key, "key");
        StateSnapshot snapshot = cache.snapshot();
        Civilization civilization = snapshot.civilization(requireText(civilizationName, "civilization"));
        if (civilization == null) {
            return new ForcePreview("forceclaim", key.compact(), false, false,
                List.of("No active civilization matches that name or cached identity."));
        }
        if (snapshot.claim(key) != null) {
            return new ForcePreview("forceclaim", key.compact(), false, false,
                List.of("The chunk is already claimed."));
        }
        List<String> warnings = claimWarnings(snapshot, civilization, key);
        return new ForcePreview("forceclaim", key.compact(), true, !warnings.isEmpty(), warnings);
    }

    /** Preview uses the immutable cache and never mutates state. */
    public ForcePreview previewForceUnclaim(ChunkKey key) {
        Objects.requireNonNull(key, "key");
        StateSnapshot snapshot = cache.snapshot();
        Claim claim = snapshot.claim(key);
        if (claim == null) return new ForcePreview("forceunclaim", key.compact(), false, false,
            List.of("The chunk is not claimed."));
        if (claim.isCapital()) return new ForcePreview("forceunclaim", key.compact(), false, false,
            List.of("A capital claim cannot be force-unclaimed; move the capital first."));
        Civilization civilization = snapshot.civilization(claim.civilizationId());
        List<String> warnings = unclaimWarnings(snapshot.claims(claim.civilizationId()), civilization, key, claim);
        return new ForcePreview("forceunclaim", key.compact(), true, !warnings.isEmpty(), warnings);
    }

    public CompletableFuture<Result> setRole(UUID actor, UUID player, Role role) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(role, "role");
        return findMemberCivilization(player).thenCompose(civilizationId -> {
            if (civilizationId == null) return CompletableFuture.completedFuture(Result.denied("That player has no active membership."));
            return database.transaction(connection -> locks.withLock(civilizationId,
                () -> setRoleTransaction(connection, actor, player, role, civilizationId)))
                .thenCompose(this::refreshAfterMutation);
        });
    }

    public CompletableFuture<Result> forceClaim(UUID actor, String civilizationName, ChunkKey key,
                                                String worldName, boolean acknowledgeWarnings) {
        Objects.requireNonNull(key, "key");
        String safeWorldName = requireText(worldName, "worldName");
        if (!settings.worlds().allowed(safeWorldName)) {
            return CompletableFuture.completedFuture(Result.denied("Claims are disabled in this world."));
        }
        return findCivilizationId(civilizationName).thenCompose(civilizationId -> {
            if (civilizationId == null) return CompletableFuture.completedFuture(Result.denied("No active civilization matches that name."));
            return database.transaction(connection -> locks.withLock(civilizationId,
                () -> forceClaimTransaction(connection, actor, civilizationId, key, safeWorldName, acknowledgeWarnings)))
                .thenCompose(this::refreshAfterMutation);
        });
    }

    public CompletableFuture<Result> forceUnclaim(UUID actor, ChunkKey key, boolean acknowledgeWarnings) {
        Objects.requireNonNull(key, "key");
        return findClaimCivilization(key).thenCompose(civilizationId -> {
            if (civilizationId == null) return CompletableFuture.completedFuture(Result.denied("That chunk is not claimed."));
            return database.transaction(connection -> locks.withLock(civilizationId,
                () -> forceUnclaimTransaction(connection, actor, key, civilizationId, acknowledgeWarnings)))
                .thenCompose(this::refreshAfterMutation);
        });
    }

    /** A signed amount adds to or removes from the stockpile; balances can never become negative. */
    public CompletableFuture<Result> grantResource(UUID actor, String civilizationName, ResourceKey key, long amount) {
        Objects.requireNonNull(key, "key");
        if (amount == 0) return CompletableFuture.completedFuture(Result.denied("The resource adjustment cannot be zero."));
        try {
            resources.require(key);
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(Result.denied(invalid.getMessage()));
        }
        return findCivilizationId(civilizationName).thenCompose(civilizationId -> {
            if (civilizationId == null) return CompletableFuture.completedFuture(Result.denied("No active civilization matches that name."));
            return database.transaction(connection -> locks.withLock(civilizationId,
                () -> grantResourceTransaction(connection, actor, civilizationId, key, amount)))
                .thenCompose(this::refreshAfterMutation);
        });
    }

    /** A signed amount adds to or removes from the Knowledge balance. */
    public CompletableFuture<Result> grantKnowledge(UUID actor, String civilizationName, long amount) {
        if (amount == 0) return CompletableFuture.completedFuture(Result.denied("The Knowledge adjustment cannot be zero."));
        return findCivilizationId(civilizationName).thenCompose(civilizationId -> {
            if (civilizationId == null) return CompletableFuture.completedFuture(Result.denied("No active civilization matches that name."));
            return database.transaction(connection -> locks.withLock(civilizationId,
                () -> grantKnowledgeTransaction(connection, actor, civilizationId, amount)))
                .thenCompose(this::refreshAfterMutation);
        });
    }

    public enum ResearchRepair { UNLOCK, LOCK }

    /**
     * Unlock/lock repair never refunds a queue's historic costs. If a matching
     * active queue must be removed, the caller has to acknowledge that warning.
     */
    public CompletableFuture<Result> repairResearch(UUID actor, ResearchRepair repair, String civilizationName,
                                                    String technologyKey, boolean acknowledgeWarnings) {
        Objects.requireNonNull(repair, "repair");
        String key = normalizeTechnologyKey(technologyKey);
        if (repair == ResearchRepair.UNLOCK && technologies.get(key) == null) {
            return CompletableFuture.completedFuture(Result.denied("Unknown technology " + key + "."));
        }
        return findCivilizationId(civilizationName).thenCompose(civilizationId -> {
            if (civilizationId == null) return CompletableFuture.completedFuture(Result.denied("No active civilization matches that name."));
            return database.transaction(connection -> locks.withLock(civilizationId, () -> repairResearchTransaction(
                connection, actor, repair, civilizationId, key, acknowledgeWarnings)))
                .thenCompose(this::refreshAfterMutation);
        });
    }

    public CompletableFuture<Result> cancelWar(UUID actor, long warId, String reason) {
        return cancelWar(actor, warId, reason, true);
    }

    public CompletableFuture<Result> cancelWar(UUID actor, long warId, String reason,
                                               boolean acknowledgeWarnings) {
        return repairWar(actor, warId, WarRepair.cancel(cleanReason(reason)), acknowledgeWarnings);
    }

    /** Admin resolution is a state repair: it intentionally performs no claim transfers. */
    public CompletableFuture<Result> resolveWar(UUID actor, long warId, String reason) {
        return resolveWar(actor, warId, reason, true);
    }

    public CompletableFuture<Result> resolveWar(UUID actor, long warId, String reason,
                                                boolean acknowledgeWarnings) {
        return repairWar(actor, warId, WarRepair.resolve(cleanReason(reason)), acknowledgeWarnings);
    }

    public CompletableFuture<Result> setWarState(UUID actor, long warId, WarState state,
                                                 boolean acknowledgeWarnings) {
        Objects.requireNonNull(state, "state");
        return repairWar(actor, warId, WarRepair.setState(state), acknowledgeWarnings);
    }

    public CompletableFuture<List<AuditEntry>> queryAudit(AuditFilter filter) {
        Objects.requireNonNull(filter, "filter");
        return database.read(connection -> queryAudit(connection, filter));
    }

    /** Durable audit companion for the command layer's synchronous safe-config reload. */
    public CompletableFuture<Result> auditReload(UUID actor, boolean success, String summary) {
        String safeSummary = summary == null || summary.isBlank() ? (success ? "Reload completed." : "Reload failed.")
            : summary.strip();
        return database.transaction(connection -> {
            AuditLog.write(connection, null, actor, success ? "admin.reload" : "admin.reload.failed",
                "configuration", "runtime", Map.of("summary", safeSummary), settings.serverId());
            return success ? Result.changed(safeSummary) : Result.denied(safeSummary);
        });
    }

    /** `/civ admin migrate` is deliberately a report; startup owns forward migrations. */
    public CompletableFuture<SchemaReport> schemaReport() {
        if (!database.healthy()) return CompletableFuture.completedFuture(new SchemaReport(false, "unavailable",
            settings.database().schema(), 0, EXPECTED_SCHEMA_VERSION, false, List.of(), List.copyOf(REQUIRED_TABLES),
            List.of("MySQL is unavailable; startup migrations cannot be inspected."), clock.instant()));
        return database.read(this::loadSchemaReport);
    }

    private Result setRoleTransaction(Connection connection, UUID actor, UUID player, Role requested,
                                      long expectedCivilizationId) throws Exception {
        MemberRow member = lockMember(connection, player);
        if (member == null || member.civilizationId != expectedCivilizationId) return Result.denied("That player's membership changed; retry the repair.");
        CivilizationRow civilization = lockCivilization(connection, expectedCivilizationId);
        if (civilization == null) return Result.denied("The civilization is no longer active.");

        if (requested != Role.LEADER && player.equals(civilization.leaderId)) {
            return Result.denied("Promote the replacement leader first; the civilization leader pointer cannot be left without a LEADER member.");
        }
        List<String> warnings = new ArrayList<>();
        boolean supportingRepair = false;
        if (requested == Role.LEADER) {
            int demoted;
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE civ_members SET role = 'ADVISOR' WHERE civ_id = ? AND role = 'LEADER' AND player_uuid <> ?")) {
                statement.setLong(1, expectedCivilizationId);
                statement.setBytes(2, UuidBytes.toBytes(player));
                demoted = statement.executeUpdate();
            }
            if (demoted > 0) {
                supportingRepair = true;
                warnings.add("Existing leader membership row(s) were demoted to ADVISOR as part of pointer repair.");
            }
            if (!player.equals(civilization.leaderId)) {
                try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE civilizations SET leader_uuid = ?, row_version = row_version + 1 WHERE id = ?")) {
                    statement.setBytes(1, UuidBytes.toBytes(player));
                    statement.setLong(2, expectedCivilizationId);
                    statement.executeUpdate();
                }
                supportingRepair = true;
            }
        }
        if (!supportingRepair && member.role == requested && (requested != Role.LEADER || player.equals(civilization.leaderId))) {
            return Result.unchanged("The player already has role " + requested + ".");
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE civ_members SET role = ? WHERE civ_id = ? AND player_uuid = ?")) {
            statement.setString(1, requested.name());
            statement.setLong(2, expectedCivilizationId);
            statement.setBytes(3, UuidBytes.toBytes(player));
            statement.executeUpdate();
        }
        AuditLog.write(connection, expectedCivilizationId, actor, "admin.member.setrole", "player", player.toString(),
            Map.of("before", member.role.name(), "after", requested.name(), "warnings", warnings), settings.serverId());
        return Result.changed("Set " + member.lastKnownName + " to " + requested + ".", warnings);
    }

    private Result forceClaimTransaction(Connection connection, UUID actor, long civilizationId, ChunkKey key,
                                         String worldName, boolean acknowledgeWarnings) throws Exception {
        CivilizationRow civilization = lockCivilization(connection, civilizationId);
        if (civilization == null) return Result.denied("The civilization is not active.");
        try (PreparedStatement occupied = Sql.prepare(connection, """
            SELECT civ_id FROM civ_claims WHERE world_uuid = ? AND chunk_x = ? AND chunk_z = ? FOR UPDATE
            """, key.worldId(), key.x(), key.z()); ResultSet result = occupied.executeQuery()) {
            if (result.next()) return Result.denied("That chunk is already claimed by civilization #" + result.getLong(1) + ".");
        }
        List<ChunkKey> currentClaims = claimKeys(connection, civilizationId);
        List<String> warnings = persistedClaimWarnings(connection, civilization, currentClaims, key);
        if (!warnings.isEmpty() && !acknowledgeWarnings) {
            return Result.confirmationRequired("Force-claim requires explicit warning acknowledgement.", warnings);
        }
        long claimId;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO civ_claims(world_uuid, world_name, chunk_x, chunk_z, civ_id, plot_type,
                acquisition_source, claim_cost_snapshot, claimed_at, claimed_by, row_version)
            VALUES (?, ?, ?, ?, ?, 'CIVIC', 'ADMIN', ?, ?, ?, 0)
            """, Statement.RETURN_GENERATED_KEYS)) {
            Sql.bind(statement, key.worldId(), worldName, key.x(), key.z(), civilizationId, JsonData.costs(Map.of()),
                clock.instant(), actor);
            statement.executeUpdate();
            claimId = Sql.generatedId(statement);
        }
        AuditLog.write(connection, civilizationId, actor, "admin.claim.force", "claim", Long.toString(claimId),
            Map.of("chunk", key.compact(), "warnings", warnings, "source", "ADMIN"), settings.serverId());
        return Result.changed("Force-claimed " + key.x() + ", " + key.z() + " for civilization #" + civilizationId + ".", warnings);
    }

    private Result forceUnclaimTransaction(Connection connection, UUID actor, ChunkKey key, long expectedCivilizationId,
                                           boolean acknowledgeWarnings) throws Exception {
        CivilizationRow civilization = lockCivilization(connection, expectedCivilizationId);
        if (civilization == null) return Result.denied("The owning civilization is not active.");
        ClaimRow claim;
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT id, civ_id, plot_type, plot_owner_uuid, listing_kind FROM civ_claims
            WHERE world_uuid = ? AND chunk_x = ? AND chunk_z = ? FOR UPDATE
            """, key.worldId(), key.x(), key.z()); ResultSet result = statement.executeQuery()) {
            if (!result.next()) return Result.denied("That chunk is no longer claimed.");
            claim = new ClaimRow(result.getLong("id"), result.getLong("civ_id"), result.getString("plot_type"),
                UuidBytes.fromBytes(result.getBytes("plot_owner_uuid")), result.getString("listing_kind"));
        }
        if (claim.civilizationId != expectedCivilizationId) return Result.denied("The claim owner changed; retry the repair.");
        if ("CAPITAL".equals(claim.plotType)) return Result.denied("A capital claim cannot be force-unclaimed; move the capital first.");
        List<Long> objectiveIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM war_objectives WHERE target_claim_id = ? FOR UPDATE")) {
            statement.setLong(1, claim.id);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) objectiveIds.add(result.getLong(1));
            }
        }
        List<UUID> economyOperations = new ArrayList<>();
        List<String> unsettledEconomyOperations = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT operation_id, state FROM economy_operations WHERE claim_id = ? FOR UPDATE")) {
            statement.setLong(1, claim.id);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID operation = UuidBytes.fromBytes(result.getBytes(1));
                    economyOperations.add(operation);
                    String state = result.getString(2);
                    if (Set.of("PENDING", "WITHDRAWAL_IN_FLIGHT", "EXTERNAL_APPLIED", "DB_APPLIED",
                        "DELIVERY_IN_FLIGHT", "COMPENSATION_PENDING", "REFUND_IN_FLIGHT",
                        "PENDING_DELIVERY").contains(state)) {
                        unsettledEconomyOperations.add(operation + " (" + state + ")");
                    }
                }
            }
        }
        if (!unsettledEconomyOperations.isEmpty()) return Result.denied(
            "The claim has unsettled economy operation(s); compensate or finalize them before force-unclaiming: "
                + String.join(", ", unsettledEconomyOperations));
        List<ChunkKey> keys = claimKeys(connection, expectedCivilizationId);
        List<String> warnings = new ArrayList<>(persistedUnclaimWarnings(keys, civilization, key, claim));
        if (!objectiveIds.isEmpty()) warnings.add(objectiveIds.size()
            + " historical/current campaign objective record(s) will be deleted to release the foreign-key reference.");
        if (!economyOperations.isEmpty()) warnings.add(economyOperations.size()
            + " economy operation reference(s) will be detached from the claim; the operations themselves remain auditable.");
        if (!warnings.isEmpty() && !acknowledgeWarnings) {
            return Result.confirmationRequired("Force-unclaim requires explicit warning acknowledgement.", warnings);
        }
        if (!objectiveIds.isEmpty()) {
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM war_objectives WHERE target_claim_id = ?")) {
                statement.setLong(1, claim.id); statement.executeUpdate();
            }
        }
        if (!economyOperations.isEmpty()) {
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE economy_operations SET claim_id = NULL WHERE claim_id = ?")) {
                statement.setLong(1, claim.id); statement.executeUpdate();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM civ_claims WHERE id = ?")) {
            statement.setLong(1, claim.id);
            statement.executeUpdate();
        }
        AuditLog.write(connection, expectedCivilizationId, actor, "admin.claim.forceunclaim", "claim", Long.toString(claim.id),
            Map.of("chunk", key.compact(), "previousPlotType", claim.plotType, "warnings", warnings,
                "deletedObjectiveIds", objectiveIds, "detachedEconomyOperations", economyOperations), settings.serverId());
        return Result.changed("Force-unclaimed " + key.x() + ", " + key.z() + ".", warnings);
    }

    private Result grantResourceTransaction(Connection connection, UUID actor, long civilizationId,
                                             ResourceKey key, long delta) throws Exception {
        if (lockCivilization(connection, civilizationId) == null) return Result.denied("The civilization is not active.");
        long before = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT quantity FROM civ_stockpile WHERE civ_id = ? AND resource_key = ? AND tier = ? FOR UPDATE
            """)) {
            statement.setLong(1, civilizationId);
            statement.setString(2, key.family());
            statement.setInt(3, key.tier());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) before = result.getLong(1);
            }
        }
        final long after;
        try {
            after = Math.addExact(before, delta);
        } catch (ArithmeticException overflow) {
            return Result.denied("That adjustment would overflow the stockpile balance.");
        }
        if (after < 0) return Result.denied("That adjustment would make the stockpile balance negative (current: " + before + ").");
        if (after == 0) {
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM civ_stockpile WHERE civ_id = ? AND resource_key = ? AND tier = ?")) {
                statement.setLong(1, civilizationId); statement.setString(2, key.family()); statement.setInt(3, key.tier());
                statement.executeUpdate();
            }
        } else {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO civ_stockpile(civ_id, resource_key, tier, quantity) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE quantity = VALUES(quantity)
                """)) {
                statement.setLong(1, civilizationId); statement.setString(2, key.family()); statement.setInt(3, key.tier());
                statement.setLong(4, after); statement.executeUpdate();
            }
        }
        UUID operationId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO stockpile_ledger(operation_id, civ_id, actor_uuid, resource_key, tier, delta,
                reason, related_type, related_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, 'ADMIN_ADJUSTMENT', 'civilization', ?, ?)
            """)) {
            Sql.bind(statement, operationId, civilizationId, actor, key.family(), key.tier(), delta,
                Long.toString(civilizationId), clock.instant());
            statement.executeUpdate();
        }
        AuditLog.write(connection, civilizationId, actor, "admin.stockpile.adjust", "resource", key.serialized(),
            Map.of("operationId", operationId.toString(), "delta", delta, "before", before, "after", after), settings.serverId());
        return Result.changed("Adjusted " + key.serialized() + " by " + signed(delta) + " (now " + after + ").");
    }

    private Result grantKnowledgeTransaction(Connection connection, UUID actor, long civilizationId,
                                              long delta) throws Exception {
        CivilizationRow civilization = lockCivilization(connection, civilizationId);
        if (civilization == null) return Result.denied("The civilization is not active.");
        final long after;
        try {
            after = Math.addExact(civilization.knowledge, delta);
        } catch (ArithmeticException overflow) {
            return Result.denied("That adjustment would overflow the Knowledge balance.");
        }
        if (after < 0) return Result.denied("That adjustment would make Knowledge negative (current: " + civilization.knowledge + ").");
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE civilizations SET knowledge_balance = ?, row_version = row_version + 1 WHERE id = ?")) {
            statement.setLong(1, after); statement.setLong(2, civilizationId); statement.executeUpdate();
        }
        AuditLog.write(connection, civilizationId, actor, "admin.knowledge.adjust", "civilization", Long.toString(civilizationId),
            Map.of("delta", delta, "before", civilization.knowledge, "after", after), settings.serverId());
        return Result.changed("Adjusted Knowledge by " + signed(delta) + " (now " + after + ").");
    }

    private Result repairResearchTransaction(Connection connection, UUID actor, ResearchRepair repair,
                                             long civilizationId, String key,
                                             boolean acknowledgeWarnings) throws Exception {
        if (lockCivilization(connection, civilizationId) == null) return Result.denied("The civilization is not active.");
        List<Long> queues = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM research_queue WHERE civ_id = ? AND technology_key = ? AND state = 'ACTIVE' FOR UPDATE")) {
            statement.setLong(1, civilizationId); statement.setString(2, key);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) queues.add(result.getLong(1));
            }
        }
        Set<String> unlocked = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT technology_key FROM civ_technologies WHERE civ_id = ? FOR UPDATE")) {
            statement.setLong(1, civilizationId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) unlocked.add(result.getString(1));
            }
        }
        List<String> warnings = new ArrayList<>();
        if (!queues.isEmpty()) warnings.add(
            "The matching active research queue will be deleted without a Knowledge or material refund.");
        if (repair == ResearchRepair.UNLOCK) {
            TechnologyDefinition definition = technologies.get(key);
            List<String> missing = definition.prerequisites().stream().filter(prerequisite -> !unlocked.contains(prerequisite)).toList();
            if (!missing.isEmpty()) warnings.add("This bypasses missing prerequisite(s): " + String.join(", ", missing) + ".");
        } else {
            List<String> dependents = new ArrayList<>();
            for (String unlockedKey : unlocked) {
                TechnologyDefinition definition = technologies.get(unlockedKey);
                if (definition != null && definition.prerequisites().contains(key)) dependents.add(unlockedKey + " (unlocked)");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT technology_key FROM research_queue WHERE civ_id = ? AND state = 'ACTIVE'")) {
                statement.setLong(1, civilizationId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        String queued = result.getString(1);
                        TechnologyDefinition definition = technologies.get(queued);
                        if (definition != null && definition.prerequisites().contains(key)) dependents.add(queued + " (queued)");
                    }
                }
            }
            if (!dependents.isEmpty()) warnings.add("Dependent research remains and may be structurally invalid: "
                + String.join(", ", dependents) + ".");
        }
        if (!warnings.isEmpty() && !acknowledgeWarnings) {
            return Result.confirmationRequired("Research repair requires explicit warning acknowledgement.", warnings);
        }
        int changed;
        if (repair == ResearchRepair.UNLOCK) {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT IGNORE INTO civ_technologies(civ_id, technology_key, unlocked_at, unlocked_by, source)
                VALUES (?, ?, ?, ?, 'ADMIN')
                """)) {
                Sql.bind(statement, civilizationId, key, clock.instant(), actor);
                changed = statement.executeUpdate();
            }
        } else {
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM civ_technologies WHERE civ_id = ? AND technology_key = ?")) {
                statement.setLong(1, civilizationId); statement.setString(2, key); changed = statement.executeUpdate();
            }
        }
        if (!queues.isEmpty()) {
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM research_queue WHERE civ_id = ? AND technology_key = ?")) {
                statement.setLong(1, civilizationId); statement.setString(2, key); changed += statement.executeUpdate();
            }
        }
        if (changed == 0) return Result.unchanged("Research state was already " + repair.name().toLowerCase(Locale.ROOT) + ".");
        AuditLog.write(connection, civilizationId, actor, "admin.research." + repair.name().toLowerCase(Locale.ROOT),
            "technology", key, Map.of("cancelledQueueIds", queues, "warnings", warnings), settings.serverId());
        return Result.changed((repair == ResearchRepair.UNLOCK ? "Unlocked " : "Locked ") + key + " for civilization #"
            + civilizationId + ".", warnings);
    }

    private CompletableFuture<Result> repairWar(UUID actor, long warId, WarRepair repair,
                                                boolean acknowledgeWarnings) {
        if (warId <= 0) return CompletableFuture.completedFuture(Result.denied("War ID must be positive."));
        return findWarParticipants(warId).thenCompose(participants -> {
            if (participants == null) return CompletableFuture.completedFuture(Result.denied("No campaign has ID " + warId + "."));
            return database.transaction(connection -> locks.withLocks(participants.attacker, participants.defender,
                () -> repairWarTransaction(connection, actor, warId, participants, repair, acknowledgeWarnings)))
                .thenCompose(this::refreshAfterMutation);
        });
    }

    private Result repairWarTransaction(Connection connection, UUID actor, long warId, WarParticipants expected,
                                        WarRepair repair, boolean acknowledgeWarnings) throws Exception {
        WarRow war = lockWar(connection, warId);
        if (war == null) return Result.denied("That campaign no longer exists.");
        if (war.attacker != expected.attacker || war.defender != expected.defender) {
            return Result.denied("Campaign participants changed; retry the repair.");
        }
        List<String> warnings = warStateWarnings(connection, war, repair.state);
        if (!warnings.isEmpty() && !acknowledgeWarnings) {
            return Result.confirmationRequired("Campaign repair requires explicit warning acknowledgement.", warnings);
        }
        if (repair.state.current() && hasOtherCurrentWar(connection, war)) {
            return Result.confirmationRequired("A participant already has another current campaign. Cancel or resolve that campaign first.",
                List.of("Setting this state now would violate the one-current-war invariant."));
        }
        Instant now = clock.instant();
        String result = repair.state.current() ? null : repair.result;
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE wars SET state = ?, result = ?, resolved_at = ?, truce_until = ?,
                winning_civ_id = CASE WHEN ? THEN NULL ELSE winning_civ_id END,
                resolution_metadata = CASE WHEN ? THEN NULL ELSE resolution_metadata END,
                row_version = row_version + 1
            WHERE id = ?
            """)) {
            statement.setString(1, repair.state.name());
            statement.setString(2, result);
            if (repair.state.current()) statement.setObject(3, null); else statement.setTimestamp(3, Timestamp.from(now));
            if (repair.state == WarState.TRUCE) {
                statement.setTimestamp(4, Timestamp.from(now.plus(settings.war().truce())));
            } else statement.setObject(4, null);
            statement.setBoolean(5, repair.state.current());
            statement.setBoolean(6, repair.state.current());
            statement.setLong(7, warId);
            statement.executeUpdate();
        }
        if (repair.failObjectives) {
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE war_objectives SET state = 'FAILED', resolved_at = ?, row_version = row_version + 1
                WHERE war_id = ? AND state <> 'TRANSFERRED' AND state <> 'FAILED'
                """)) {
                statement.setTimestamp(1, Timestamp.from(now)); statement.setLong(2, warId); statement.executeUpdate();
            }
        }
        if (repair.state.current()) setWarPointers(connection, war, warId);
        else clearWarPointersAndLocks(connection, war, warId);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("before", war.state.name());
        metadata.put("after", repair.state.name());
        metadata.put("reason", repair.reason);
        metadata.put("warnings", warnings);
        metadata.put("claimTransfersPerformed", false);
        AuditLog.write(connection, war.attacker, actor, "admin.war.repair", "war", Long.toString(warId), metadata, settings.serverId());
        AuditLog.write(connection, war.defender, actor, "admin.war.repair", "war", Long.toString(warId), metadata, settings.serverId());
        return Result.changed("Campaign #" + warId + " state changed from " + war.state + " to " + repair.state
            + ". No claim transfers were performed.", warnings);
    }

    private Long loadCivilizationInspection(Connection connection, String input, Map<String, String> values) throws Exception {
        Long numericId = parseId(input);
        String sql = numericId == null ? """
            SELECT c.*,
              (SELECT COUNT(*) FROM civ_members m WHERE m.civ_id = c.id) AS member_count,
              (SELECT COUNT(*) FROM civ_members m WHERE m.civ_id = c.id AND m.established = TRUE AND m.role <> 'LEADER') AS established_count,
              (SELECT COUNT(*) FROM civ_claims cl WHERE cl.civ_id = c.id) AS claim_count,
              (SELECT COUNT(*) FROM research_queue rq WHERE rq.civ_id = c.id AND rq.state = 'ACTIVE') AS research_count
            FROM civilizations c WHERE c.normalized_name = ? ORDER BY c.id LIMIT 1
            """ : """
            SELECT c.*,
              (SELECT COUNT(*) FROM civ_members m WHERE m.civ_id = c.id) AS member_count,
              (SELECT COUNT(*) FROM civ_members m WHERE m.civ_id = c.id AND m.established = TRUE AND m.role <> 'LEADER') AS established_count,
              (SELECT COUNT(*) FROM civ_claims cl WHERE cl.civ_id = c.id) AS claim_count,
              (SELECT COUNT(*) FROM research_queue rq WHERE rq.civ_id = c.id AND rq.state = 'ACTIVE') AS research_count
            FROM civilizations c WHERE c.id = ? LIMIT 1
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (numericId == null) {
                String normalized = NameNormalizer.normalize(input);
                statement.setString(1, normalized);
            } else statement.setLong(1, numericId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    values.put("found", "false");
                    return null;
                }
                long id = result.getLong("id");
                values.put("found", "true");
                put(values, "id", id);
                put(values, "name", result.getString("name"));
                put(values, "normalizedName", result.getString("normalized_name"));
                put(values, "status", result.getString("status"));
                put(values, "leader", UuidBytes.fromBytes(result.getBytes("leader_uuid")));
                put(values, "capital", chunk(result, "capital_world_uuid", "capital_chunk_x", "capital_chunk_z"));
                put(values, "capitalWorldName", result.getString("capital_world_name"));
                put(values, "treasury", result.getBigDecimal("treasury_balance"));
                put(values, "knowledge", result.getLong("knowledge_balance"));
                put(values, "peaceShieldUntil", TimeUtil.instant(result.getTimestamp("peace_shield_until")));
                put(values, "currentWarId", nullableLong(result, "current_war_id"));
                put(values, "adminClaimBonus", result.getInt("admin_claim_bonus"));
                put(values, "memberCount", result.getLong("member_count"));
                put(values, "establishedNonLeaderCount", result.getLong("established_count"));
                put(values, "claimCount", result.getLong("claim_count"));
                put(values, "activeResearchCount", result.getLong("research_count"));
                put(values, "rowVersion", result.getLong("row_version"));
                put(values, "createdAt", TimeUtil.instant(result.getTimestamp("created_at")));
                loadCivilizationCollections(connection, id, values);
                return id;
            }
        }
    }

    private void loadCivilizationCollections(Connection connection, long civilizationId,
                                               Map<String, String> values) throws Exception {
        List<String> stockpile = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT resource_key, tier, quantity FROM civ_stockpile WHERE civ_id = ? ORDER BY resource_key, tier
            """)) {
            statement.setLong(1, civilizationId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) stockpile.add(result.getString(1) + ":" + result.getInt(2) + "=" + result.getLong(3));
            }
        }
        put(values, "stockpile", String.join(", ", stockpile));
        List<String> unlocked = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT technology_key FROM civ_technologies WHERE civ_id = ? ORDER BY technology_key")) {
            statement.setLong(1, civilizationId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) unlocked.add(result.getString(1));
            }
        }
        put(values, "technologies", String.join(", ", unlocked));
        List<String> research = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT queue_slot, technology_key, completes_at, state FROM research_queue WHERE civ_id = ? ORDER BY queue_slot
            """)) {
            statement.setLong(1, civilizationId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) research.add("slot " + result.getInt(1) + ":" + result.getString(2) + " "
                    + result.getString(4) + " due=" + text(TimeUtil.instant(result.getTimestamp(3))));
            }
        }
        put(values, "research", String.join("; ", research));
    }

    private Long loadClaimInspection(Connection connection, ChunkKey key, Map<String, String> values) throws Exception {
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT cl.*, c.name AS civ_name,
              (SELECT COUNT(*) FROM plot_trust pt WHERE pt.claim_id = cl.id) AS trust_count,
              (SELECT COUNT(*) FROM war_objectives wo WHERE wo.target_claim_id = cl.id) AS objective_count
            FROM civ_claims cl LEFT JOIN civilizations c ON c.id = cl.civ_id
            WHERE cl.world_uuid = ? AND cl.chunk_x = ? AND cl.chunk_z = ?
            """, key.worldId(), key.x(), key.z()); ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                values.put("found", "false");
                return null;
            }
            long id = result.getLong("id");
            values.put("found", "true");
            put(values, "id", id);
            put(values, "chunk", key);
            put(values, "worldName", result.getString("world_name"));
            put(values, "civilizationId", result.getLong("civ_id"));
            put(values, "civilizationName", result.getString("civ_name"));
            put(values, "plotType", result.getString("plot_type"));
            put(values, "plotOwner", UuidBytes.fromBytes(result.getBytes("plot_owner_uuid")));
            put(values, "listingKind", result.getString("listing_kind"));
            put(values, "listingSeller", UuidBytes.fromBytes(result.getBytes("listing_seller_uuid")));
            put(values, "listingPrice", result.getBigDecimal("listing_price"));
            put(values, "source", result.getString("acquisition_source"));
            put(values, "claimedAt", TimeUtil.instant(result.getTimestamp("claimed_at")));
            put(values, "claimedBy", UuidBytes.fromBytes(result.getBytes("claimed_by")));
            put(values, "trustedPlayers", result.getLong("trust_count"));
            put(values, "warObjectiveReferences", result.getLong("objective_count"));
            put(values, "rowVersion", result.getLong("row_version"));
            return id;
        }
    }

    private Long loadPlayerInspection(Connection connection, UUID playerId, Map<String, String> values) throws Exception {
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT m.*, c.name AS civ_name, c.status AS civ_status, c.leader_uuid
            FROM civ_members m LEFT JOIN civilizations c ON c.id = m.civ_id WHERE m.player_uuid = ?
            """, playerId); ResultSet result = statement.executeQuery()) {
            if (result.next()) {
                long civilizationId = result.getLong("civ_id");
                values.put("found", "true");
                put(values, "civilizationId", civilizationId);
                put(values, "civilizationName", result.getString("civ_name"));
                put(values, "civilizationStatus", result.getString("civ_status"));
                put(values, "lastKnownName", result.getString("last_known_name"));
                put(values, "role", result.getString("role"));
                put(values, "isLeaderPointer", playerId.equals(UuidBytes.fromBytes(result.getBytes("leader_uuid"))));
                put(values, "joinedAt", TimeUtil.instant(result.getTimestamp("joined_at")));
                put(values, "lastActiveAt", TimeUtil.instant(result.getTimestamp("last_active_at")));
                put(values, "eligiblePlaytimeSeconds", result.getLong("eligible_playtime_seconds"));
                put(values, "established", result.getBoolean("established"));
                put(values, "contributionTotal", result.getLong("contribution_total"));
                put(values, "membershipLocked", result.getBoolean("membership_locked"));
                loadPlayerAuxiliary(connection, playerId, values);
                return civilizationId;
            }
        }
        values.put("found", "false");
        loadPlayerAuxiliary(connection, playerId, values);
        return null;
    }

    private void loadPlayerAuxiliary(Connection connection, UUID playerId, Map<String, String> values) throws Exception {
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT last_known_name, chat_mode, locale, updated_at FROM player_settings WHERE player_uuid = ?
            """, playerId); ResultSet result = statement.executeQuery()) {
            if (result.next()) {
                put(values, "settingsName", result.getString("last_known_name"));
                put(values, "chatMode", result.getBoolean("chat_mode"));
                put(values, "locale", result.getString("locale"));
                put(values, "settingsUpdatedAt", TimeUtil.instant(result.getTimestamp("updated_at")));
            }
        }
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT COUNT(*) AS operation_count,
              SUM(CASE WHEN state IN ('PENDING','WITHDRAWAL_IN_FLIGHT','EXTERNAL_APPLIED','DB_APPLIED',
                                      'DELIVERY_IN_FLIGHT','COMPENSATION_PENDING','REFUND_IN_FLIGHT',
                                      'PENDING_DELIVERY') THEN 1 ELSE 0 END) AS pending_count
            FROM economy_operations WHERE player_uuid = ? OR beneficiary_uuid = ?
            """, playerId, playerId); ResultSet result = statement.executeQuery()) {
            result.next();
            put(values, "economyOperationCount", result.getLong("operation_count"));
            put(values, "unsettledEconomyOperationCount", result.getLong("pending_count"));
        }
    }

    private Long loadWarInspection(Connection connection, long warId, Map<String, String> values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT w.*, a.name AS attacker_name, d.name AS defender_name,
              (SELECT COUNT(*) FROM war_roster r WHERE r.war_id = w.id) AS roster_count,
              (SELECT COUNT(*) FROM war_objectives o WHERE o.war_id = w.id) AS objective_count
            FROM wars w JOIN civilizations a ON a.id = w.attacker_civ_id
              JOIN civilizations d ON d.id = w.defender_civ_id WHERE w.id = ?
            """)) {
            statement.setLong(1, warId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    values.put("found", "false");
                    return null;
                }
                values.put("found", "true");
                put(values, "id", warId);
                put(values, "attackerId", result.getLong("attacker_civ_id"));
                put(values, "attackerName", result.getString("attacker_name"));
                put(values, "defenderId", result.getLong("defender_civ_id"));
                put(values, "defenderName", result.getString("defender_name"));
                put(values, "state", result.getString("state"));
                put(values, "declaredAt", TimeUtil.instant(result.getTimestamp("declared_at")));
                put(values, "scheduledStart", TimeUtil.instant(result.getTimestamp("scheduled_start")));
                put(values, "scheduledEnd", TimeUtil.instant(result.getTimestamp("scheduled_end")));
                put(values, "timezone", result.getString("timezone_id"));
                put(values, "truceUntil", TimeUtil.instant(result.getTimestamp("truce_until")));
                put(values, "result", result.getString("result"));
                put(values, "winner", nullableLong(result, "winning_civ_id"));
                put(values, "attackerPeace", result.getBoolean("attacker_peace"));
                put(values, "defenderPeace", result.getBoolean("defender_peace"));
                put(values, "rosterCount", result.getLong("roster_count"));
                put(values, "objectiveCount", result.getLong("objective_count"));
                put(values, "rowVersion", result.getLong("row_version"));
            }
        }
        List<String> objectives = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, nominating_civ_id, target_claim_id, frozen_chunk_x, frozen_chunk_z, state,
                   accumulated_control_ms FROM war_objectives WHERE war_id = ? ORDER BY id
            """)) {
            statement.setLong(1, warId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) objectives.add("#" + result.getLong(1) + " nominator=" + result.getLong(2)
                    + " claim=" + result.getLong(3) + " chunk=" + result.getInt(4) + "," + result.getInt(5)
                    + " state=" + result.getString(6) + " controlMs=" + result.getLong(7));
            }
        }
        put(values, "objectives", String.join("; ", objectives));
        return warId;
    }

    private Map<String, String> civilizationValues(StateSnapshot snapshot, Civilization civilization) {
        Map<String, String> values = baseCacheValues(snapshot);
        if (civilization == null) {
            values.put("found", "false");
            return values;
        }
        values.put("found", "true");
        put(values, "id", civilization.id());
        put(values, "name", civilization.name());
        put(values, "status", civilization.status());
        put(values, "leader", civilization.leaderId());
        put(values, "capital", civilization.capital());
        put(values, "treasury", civilization.treasury());
        put(values, "knowledge", civilization.knowledge());
        put(values, "peaceShieldUntil", civilization.peaceShieldUntil());
        put(values, "currentWarId", civilization.currentWarId());
        put(values, "adminClaimBonus", civilization.adminClaimBonus());
        put(values, "memberCount", snapshot.members(civilization.id()).size());
        put(values, "establishedNonLeaderCount", snapshot.establishedNonLeaders(civilization.id()));
        put(values, "claimCount", snapshot.claims(civilization.id()).size());
        put(values, "claimCapacity", TerritoryRules.claimCapacity(snapshot, civilization.id(), settings.claims(), technologies));
        put(values, "age", technologies.age(snapshot.technologies(civilization.id())));
        put(values, "technologies", String.join(", ", sorted(snapshot.technologies(civilization.id()))));
        put(values, "activeResearchCount", snapshot.research(civilization.id()).size());
        put(values, "rowVersion", civilization.rowVersion());
        return values;
    }

    private Map<String, String> claimValues(StateSnapshot snapshot, Claim claim) {
        Map<String, String> values = baseCacheValues(snapshot);
        if (claim == null) {
            values.put("found", "false");
            return values;
        }
        values.put("found", "true");
        put(values, "id", claim.id());
        put(values, "chunk", claim.key());
        put(values, "worldName", claim.worldName());
        put(values, "civilizationId", claim.civilizationId());
        Civilization owner = snapshot.civilization(claim.civilizationId());
        put(values, "civilizationName", owner == null ? null : owner.name());
        put(values, "plotType", claim.plotType());
        put(values, "plotOwner", claim.plotOwnerId());
        put(values, "listingKind", claim.listingKind());
        put(values, "listingSeller", claim.listingSellerId());
        put(values, "listingPrice", claim.listingPrice());
        put(values, "source", claim.source());
        put(values, "claimedAt", claim.claimedAt());
        put(values, "claimedBy", claim.claimedBy());
        put(values, "trustedPlayers", claim.trustedPlayers().size());
        put(values, "rowVersion", claim.rowVersion());
        return values;
    }

    private Map<String, String> memberValues(StateSnapshot snapshot, Member member) {
        Map<String, String> values = baseCacheValues(snapshot);
        if (member == null) {
            values.put("found", "false");
            return values;
        }
        values.put("found", "true");
        put(values, "civilizationId", member.civilizationId());
        Civilization civilization = snapshot.civilization(member.civilizationId());
        put(values, "civilizationName", civilization == null ? null : civilization.name());
        put(values, "lastKnownName", member.lastKnownName());
        put(values, "role", member.role());
        put(values, "isLeaderPointer", civilization != null && member.playerId().equals(civilization.leaderId()));
        put(values, "joinedAt", member.joinedAt());
        put(values, "lastActiveAt", member.lastActiveAt());
        put(values, "eligiblePlaytimeSeconds", member.eligiblePlaytimeSeconds());
        put(values, "established", member.established());
        put(values, "contributionTotal", member.contributionTotal());
        put(values, "membershipLocked", member.membershipLocked());
        return values;
    }

    private Map<String, String> warValues(StateSnapshot snapshot, War war) {
        Map<String, String> values = baseCacheValues(snapshot);
        if (war == null) {
            values.put("found", "false");
            return values;
        }
        values.put("found", "true");
        put(values, "id", war.id());
        put(values, "attackerId", war.attackerCivilizationId());
        put(values, "defenderId", war.defenderCivilizationId());
        put(values, "state", war.state());
        put(values, "effectiveState", war.effectiveState(clock.instant()));
        put(values, "declaredAt", war.declaredAt());
        put(values, "scheduledStart", war.scheduledStart());
        put(values, "scheduledEnd", war.scheduledEnd());
        put(values, "truceUntil", war.truceUntil());
        put(values, "attackerPeace", war.attackerPeace());
        put(values, "defenderPeace", war.defenderPeace());
        put(values, "attackerRosterCount", war.attackerRoster().size());
        put(values, "defenderRosterCount", war.defenderRoster().size());
        put(values, "objectiveCount", snapshot.objectivesByWar().getOrDefault(war.id(), List.of()).size());
        return values;
    }

    private Map<String, String> baseCacheValues(StateSnapshot snapshot) {
        Map<String, String> values = new LinkedHashMap<>();
        put(values, "cacheReady", cache.ready());
        put(values, "cacheLoadedAt", snapshot.loadedAt());
        return values;
    }

    private List<String> identityWarnings(String subject, Long cachedId, Long persistedId, Instant cacheLoadedAt) {
        List<String> warnings = new ArrayList<>();
        if (!Objects.equals(cachedId, persistedId)) {
            warnings.add("Cached and persisted " + subject + " identities differ (cache=" + text(cachedId)
                + ", database=" + text(persistedId) + ").");
        }
        if (!cache.ready()) warnings.add("The state cache is not marked ready.");
        if (cacheLoadedAt.equals(Instant.EPOCH)) warnings.add("The state cache has never completed a load.");
        return List.copyOf(warnings);
    }

    private CompletableFuture<Long> findCivilizationId(String name) {
        String input = requireText(name, "civilization");
        Long numeric = parseId(input);
        return database.read(connection -> {
            String sql = numeric == null
                ? "SELECT id FROM civilizations WHERE status = 'ACTIVE' AND normalized_name = ? LIMIT 1"
                : "SELECT id FROM civilizations WHERE status = 'ACTIVE' AND id = ? LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                if (numeric == null) {
                    String normalized = NameNormalizer.normalize(input);
                    statement.setString(1, normalized);
                } else statement.setLong(1, numeric);
                try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getLong(1) : null; }
            }
        });
    }

    private CompletableFuture<Long> findMemberCivilization(UUID player) {
        return database.read(connection -> {
            try (PreparedStatement statement = Sql.prepare(connection,
                "SELECT civ_id FROM civ_members WHERE player_uuid = ?", player); ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        });
    }

    private CompletableFuture<Long> findClaimCivilization(ChunkKey key) {
        return database.read(connection -> {
            try (PreparedStatement statement = Sql.prepare(connection, """
                SELECT civ_id FROM civ_claims WHERE world_uuid = ? AND chunk_x = ? AND chunk_z = ?
                """, key.worldId(), key.x(), key.z()); ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        });
    }

    private CompletableFuture<WarParticipants> findWarParticipants(long warId) {
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT attacker_civ_id, defender_civ_id FROM wars WHERE id = ?")) {
                statement.setLong(1, warId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? new WarParticipants(result.getLong(1), result.getLong(2)) : null;
                }
            }
        });
    }

    private CompletableFuture<Result> refreshAfterMutation(Result result) {
        if (!result.changed()) return CompletableFuture.completedFuture(result);
        return cache.refreshAfterMutation().handle((ignored, error) -> {
            if (error == null) return result;
            List<String> warnings = new ArrayList<>(result.warnings());
            warnings.add("The database commit succeeded, but the cache refresh failed: " + rootMessage(error));
            return new Result(result.success(), true, result.message(), warnings);
        });
    }

    private CivilizationRow lockCivilization(Connection connection, long civilizationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, leader_uuid, capital_world_uuid, capital_chunk_x, capital_chunk_z,
                   current_war_id, admin_claim_bonus, knowledge_balance
            FROM civilizations WHERE id = ? AND status = 'ACTIVE' FOR UPDATE
            """)) {
            statement.setLong(1, civilizationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new CivilizationRow(result.getLong("id"), UuidBytes.fromBytes(result.getBytes("leader_uuid")),
                    chunk(result, "capital_world_uuid", "capital_chunk_x", "capital_chunk_z"),
                    nullableLong(result, "current_war_id"), result.getInt("admin_claim_bonus"),
                    result.getLong("knowledge_balance"));
            }
        }
    }

    private MemberRow lockMember(Connection connection, UUID player) throws Exception {
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT civ_id, last_known_name, role FROM civ_members WHERE player_uuid = ? FOR UPDATE
            """, player); ResultSet result = statement.executeQuery()) {
            return result.next() ? new MemberRow(result.getLong("civ_id"), result.getString("last_known_name"),
                Role.valueOf(result.getString("role"))) : null;
        }
    }

    private WarRow lockWar(Connection connection, long warId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, attacker_civ_id, defender_civ_id, state, scheduled_start, scheduled_end
            FROM wars WHERE id = ? FOR UPDATE
            """)) {
            statement.setLong(1, warId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new WarRow(result.getLong("id"), result.getLong("attacker_civ_id"),
                    result.getLong("defender_civ_id"), WarState.valueOf(result.getString("state")),
                    result.getTimestamp("scheduled_start").toInstant(), result.getTimestamp("scheduled_end").toInstant());
            }
        }
    }

    private List<ChunkKey> claimKeys(Connection connection, long civilizationId) throws Exception {
        List<ChunkKey> keys = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT world_uuid, chunk_x, chunk_z FROM civ_claims WHERE civ_id = ? FOR UPDATE")) {
            statement.setLong(1, civilizationId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) keys.add(chunk(result, "world_uuid", "chunk_x", "chunk_z"));
            }
        }
        return keys;
    }

    private List<String> claimWarnings(StateSnapshot snapshot, Civilization civilization, ChunkKey key) {
        List<String> warnings = new ArrayList<>();
        List<Claim> claims = snapshot.claims(civilization.id());
        boolean adjacent = claims.stream().anyMatch(claim -> claim.key().cardinallyAdjacent(key));
        if (!adjacent) warnings.add("The new claim is not cardinally adjacent and will create disconnected territory.");
        int capacity = TerritoryRules.claimCapacity(snapshot, civilization.id(), settings.claims(), technologies);
        if (claims.size() + 1 > capacity) warnings.add("The claim exceeds capacity (result " + (claims.size() + 1) + "/" + capacity + ").");
        if (civilization.warLocked()) warnings.add("The civilization has a current campaign; ordinary claiming is frozen.");
        return List.copyOf(warnings);
    }

    private List<String> persistedClaimWarnings(Connection connection, CivilizationRow civilization,
                                                List<ChunkKey> current, ChunkKey key) throws Exception {
        List<String> warnings = new ArrayList<>();
        if (current.stream().noneMatch(value -> value.cardinallyAdjacent(key))) {
            warnings.add("The new claim is not cardinally adjacent and will create disconnected territory.");
        }
        int capacity = persistedClaimCapacity(connection, civilization);
        if (current.size() + 1 > capacity) warnings.add("The claim exceeds capacity (result " + (current.size() + 1) + "/" + capacity + ").");
        if (civilization.currentWarId != null) warnings.add("The civilization has a current campaign; ordinary claiming is frozen.");
        return List.copyOf(warnings);
    }

    private int persistedClaimCapacity(Connection connection, CivilizationRow civilization) throws Exception {
        int established = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT COUNT(*) FROM civ_members WHERE civ_id = ? AND established = TRUE AND role <> 'LEADER'
            """)) {
            statement.setLong(1, civilization.id);
            try (ResultSet result = statement.executeQuery()) { result.next(); established = result.getInt(1); }
        }
        long bonus = 0;
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT technology_key FROM civ_technologies WHERE civ_id = ?")) {
            statement.setLong(1, civilization.id);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    TechnologyDefinition definition = technologies.get(result.getString(1));
                    if (definition != null) bonus += Math.max(0, definition.modifiers().getOrDefault("claim-capacity", 0));
                }
            }
        }
        long calculated = settings.claims().baseCapacity()
            + (long) established * settings.claims().establishedMemberBonus()
            + civilization.adminClaimBonus + bonus;
        return (int) Math.max(0, Math.min(settings.claims().absoluteCap(), calculated));
    }

    private List<String> unclaimWarnings(List<Claim> claims, Civilization civilization, ChunkKey removal, Claim claim) {
        List<ChunkKey> keys = claims.stream().map(Claim::key).toList();
        ClaimRow row = new ClaimRow(claim.id(), claim.civilizationId(), claim.plotType().name(), claim.plotOwnerId(), claim.listingKind());
        return persistedUnclaimWarnings(keys, new CivilizationRow(civilization.id(), civilization.leaderId(), civilization.capital(),
            civilization.currentWarId(), civilization.adminClaimBonus(), civilization.knowledge()), removal, row);
    }

    static List<String> persistedUnclaimWarnings(List<ChunkKey> current, CivilizationRow civilization,
                                                 ChunkKey removal, ClaimRow claim) {
        List<String> warnings = new ArrayList<>();
        Set<ChunkKey> keys = Set.copyOf(current);
        if (!Connectivity.remainsConnectedAfterRemoval(keys, civilization.capital, removal)) {
            warnings.add("Removing this claim disconnects territory from the capital.");
        }
        if ("PRIVATE".equals(claim.plotType)) warnings.add("The private plot owner's rights will be terminated without compensation.");
        if (claim.listingKind != null) warnings.add("The active plot listing will be terminated.");
        if (civilization.currentWarId != null) warnings.add("The civilization has a current campaign; ordinary unclaiming is frozen.");
        return List.copyOf(warnings);
    }

    private List<String> warStateWarnings(Connection connection, WarRow war, WarState requested) throws Exception {
        List<String> warnings = new ArrayList<>();
        Instant now = clock.instant();
        if (requested == WarState.PENDING && !now.isBefore(war.start)) {
            warnings.add("The campaign start is in the past; transition reconciliation may immediately advance it.");
        }
        if (requested == WarState.ACTIVE && (now.isBefore(war.start) || !now.isBefore(war.end))) {
            warnings.add("The current time is outside the persisted campaign window.");
        }
        if (requested == WarState.RESOLVING && now.isBefore(war.end)) {
            warnings.add("The persisted campaign end is still in the future.");
        }
        if (!requested.current()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM war_objectives WHERE war_id = ? AND state NOT IN ('FAILED','TRANSFERRED')
                """)) {
                statement.setLong(1, war.id);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    long objectives = result.getLong(1);
                    if (objectives > 0) warnings.add(objectives + " unresolved objective(s) will be marked FAILED without claim transfer.");
                }
            }
        }
        return List.copyOf(warnings);
    }

    private boolean hasOtherCurrentWar(Connection connection, WarRow war) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM wars WHERE id <> ? AND state IN ('PENDING','ACTIVE','RESOLVING')
              AND (attacker_civ_id IN (?, ?) OR defender_civ_id IN (?, ?)) LIMIT 1 FOR UPDATE
            """)) {
            statement.setLong(1, war.id); statement.setLong(2, war.attacker); statement.setLong(3, war.defender);
            statement.setLong(4, war.attacker); statement.setLong(5, war.defender);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private void setWarPointers(Connection connection, WarRow war, long warId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE civilizations SET current_war_id = ?, row_version = row_version + 1 WHERE id IN (?, ?)
            """)) {
            statement.setLong(1, warId); statement.setLong(2, war.attacker); statement.setLong(3, war.defender);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE civ_members SET membership_locked = TRUE WHERE civ_id IN (?, ?)")) {
            statement.setLong(1, war.attacker); statement.setLong(2, war.defender); statement.executeUpdate();
        }
    }

    private void clearWarPointersAndLocks(Connection connection, WarRow war, long warId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE civilizations SET current_war_id = NULL, row_version = row_version + 1
            WHERE id IN (?, ?) AND current_war_id = ?
            """)) {
            statement.setLong(1, war.attacker); statement.setLong(2, war.defender); statement.setLong(3, warId);
            statement.executeUpdate();
        }
        for (long civilizationId : new long[]{war.attacker, war.defender}) {
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE civ_members SET membership_locked = FALSE WHERE civ_id = ? AND NOT EXISTS (
                  SELECT 1 FROM wars w WHERE w.state IN ('PENDING','ACTIVE','RESOLVING')
                    AND (w.attacker_civ_id = ? OR w.defender_civ_id = ?)
                )
                """)) {
                statement.setLong(1, civilizationId); statement.setLong(2, civilizationId); statement.setLong(3, civilizationId);
                statement.executeUpdate();
            }
        }
    }

    private List<AuditEntry> queryAudit(Connection connection, AuditFilter filter) throws Exception {
        StringBuilder sql = new StringBuilder("""
            SELECT id, civ_id, actor_uuid, action_key, target_type, target_id, metadata, server_id, created_at
            FROM civ_audit_log WHERE 1=1
            """);
        List<Object> parameters = new ArrayList<>();
        if (filter.civilizationId() != null) { sql.append(" AND civ_id = ?"); parameters.add(filter.civilizationId()); }
        if (filter.actorId() != null) { sql.append(" AND actor_uuid = ?"); parameters.add(filter.actorId()); }
        if (filter.actionPrefix() != null) { sql.append(" AND action_key LIKE ?"); parameters.add(escapeLike(filter.actionPrefix()) + "%"); }
        if (filter.targetType() != null) { sql.append(" AND target_type = ?"); parameters.add(filter.targetType()); }
        if (filter.targetId() != null) { sql.append(" AND target_id = ?"); parameters.add(filter.targetId()); }
        if (filter.since() != null) { sql.append(" AND created_at >= ?"); parameters.add(filter.since()); }
        if (filter.before() != null) { sql.append(" AND created_at < ?"); parameters.add(filter.before()); }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        parameters.add(filter.pageSize());
        parameters.add(Math.multiplyExact(filter.page() - 1, filter.pageSize()));
        List<AuditEntry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            Sql.bind(statement, parameters.toArray());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) entries.add(new AuditEntry(result.getLong("id"), nullableLong(result, "civ_id"),
                    UuidBytes.fromBytes(result.getBytes("actor_uuid")), result.getString("action_key"),
                    result.getString("target_type"), result.getString("target_id"), result.getString("metadata"),
                    result.getString("server_id"), result.getTimestamp("created_at").toInstant()));
            }
        }
        return List.copyOf(entries);
    }

    private SchemaReport loadSchemaReport(Connection connection) throws Exception {
        Instant inspectedAt = clock.instant();
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName() + " " + metadata.getDatabaseProductVersion();
        List<Migration> migrations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int current = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT version, description, checksum, installed_at, success FROM schema_history ORDER BY version
            """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                int version = result.getInt("version");
                current = Math.max(current, version);
                String installed = result.getString("checksum");
                boolean success = result.getBoolean("success");
                String resource = PACKAGED_MIGRATIONS.get(version);
                String packaged = resource == null ? null : packagedChecksum(resource);
                String status;
                if (!success) status = "FAILED";
                else if (packaged == null) status = "UNKNOWN_TO_BUILD";
                else if (!packaged.equals(installed)) status = "CHECKSUM_MISMATCH";
                else status = "APPLIED";
                if (!"APPLIED".equals(status)) warnings.add("Migration V" + version + " status is " + status + ".");
                migrations.add(new Migration(version, result.getString("description"), installed, packaged, success,
                    TimeUtil.instant(result.getTimestamp("installed_at")), status));
            }
        }
        Set<String> actualTables = new LinkedHashSet<>();
        try (ResultSet tables = metadata.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) actualTables.add(tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
        }
        List<String> missing = REQUIRED_TABLES.stream().filter(table -> !actualTables.contains(table)).sorted().toList();
        if (!missing.isEmpty()) warnings.add("Required tables are missing: " + String.join(", ", missing));
        if (current < EXPECTED_SCHEMA_VERSION) warnings.add("Pending packaged migrations exist.");
        if (current > EXPECTED_SCHEMA_VERSION) warnings.add("The database is newer than this plugin build; do not run mutations.");
        boolean appliedExpected = migrations.stream().anyMatch(value -> value.version() == EXPECTED_SCHEMA_VERSION
            && "APPLIED".equals(value.status()));
        boolean upToDate = current == EXPECTED_SCHEMA_VERSION && appliedExpected && missing.isEmpty() && warnings.isEmpty();
        return new SchemaReport(true, product, connection.getCatalog(), current, EXPECTED_SCHEMA_VERSION, upToDate,
            migrations, missing, warnings, inspectedAt);
    }

    private Civilization cachedCivilization(StateSnapshot snapshot, String input) {
        Long id = parseId(input);
        return id == null ? snapshot.civilization(input) : snapshot.civilization(id);
    }

    private static Long parseId(String input) {
        try {
            long value = Long.parseLong(input);
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalizeTechnologyKey(String input) {
        String key = requireText(input, "technology").toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z0-9_]{2,64}")) throw new IllegalArgumentException("Invalid technology key: " + input);
        return key;
    }

    private static String cleanReason(String reason) {
        if (reason == null || reason.isBlank()) return "ADMIN_REPAIR";
        String clean = reason.strip();
        return clean.length() <= 190 ? clean : clean.substring(0, 190);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
        return value.strip();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String packagedChecksum(String resource) throws Exception {
        try (InputStream stream = AdminService.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing packaged migration " + resource);
            byte[] bytes = stream.readAllBytes();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
    }

    private static ChunkKey chunk(ResultSet result, String worldColumn, String xColumn, String zColumn) throws Exception {
        return new ChunkKey(UuidBytes.fromBytes(result.getBytes(worldColumn)), result.getInt(xColumn), result.getInt(zColumn));
    }

    private static Long nullableLong(ResultSet result, String column) throws Exception {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static void put(Map<String, String> values, String key, Object value) {
        values.put(key, text(value));
    }

    private static String text(Object value) {
        return value == null ? "<none>" : String.valueOf(value);
    }

    private static String signed(long value) {
        return value > 0 ? "+" + value : Long.toString(value);
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    static record CivilizationRow(long id, UUID leaderId, ChunkKey capital, Long currentWarId,
                                  int adminClaimBonus, long knowledge) {}
    static record ClaimRow(long id, long civilizationId, String plotType, UUID plotOwner, String listingKind) {}
    private record MemberRow(long civilizationId, String lastKnownName, Role role) {}
    private record WarParticipants(long attacker, long defender) {}
    private record WarRow(long id, long attacker, long defender, WarState state, Instant start, Instant end) {}

    private record WarRepair(WarState state, String result, String reason, boolean failObjectives) {
        static WarRepair cancel(String reason) {
            return new WarRepair(WarState.CANCELLED, "ADMIN_CANCELLED", reason, true);
        }

        static WarRepair resolve(String reason) {
            return new WarRepair(WarState.COMPLETED, "ADMIN_RESOLVED", reason, true);
        }

        static WarRepair setState(WarState state) {
            return new WarRepair(state, "ADMIN_SETSTATE", "ADMIN_SETSTATE", !state.current());
        }
    }
}
