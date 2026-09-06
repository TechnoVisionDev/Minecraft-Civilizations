package io.github.empireage.civilizations.service.territory;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.concurrent.CivilizationLocks;
import io.github.empireage.civilizations.config.ResourceCatalog;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.config.ResourceText;
import io.github.empireage.civilizations.config.TechnologyCatalog;
import io.github.empireage.civilizations.database.AuditLog;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.database.JsonData;
import io.github.empireage.civilizations.database.Sql;
import io.github.empireage.civilizations.domain.Claim;
import io.github.empireage.civilizations.domain.ClaimSource;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.HomeLocation;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.domain.PlotType;
import io.github.empireage.civilizations.domain.ResourceKey;
import io.github.empireage.civilizations.domain.Role;
import io.github.empireage.civilizations.util.Connectivity;
import io.github.empireage.civilizations.util.UuidBytes;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Transactional sovereign-claim service. All Bukkit/world facts are supplied by
 * the caller before the operation enters the database executor.
 */
public final class TerritoryService {
    private static final String CONFIRM_CLAIM = "territory:claim";
    private static final String CONFIRM_UNCLAIM = "territory:unclaim";
    private static final String CONFIRM_CAPITAL = "territory:capital";

    private final Database database;
    private final StateCache cache;
    private final CivilizationLocks locks;
    private final Settings settings;
    private final Supplier<ResourceCatalog> resources;
    private final Supplier<TechnologyCatalog> technologies;
    private final ClaimEnvironmentPort environment;
    private final ConfirmationTokens confirmations;
    private final Clock clock;

    public TerritoryService(Database database, StateCache cache, CivilizationLocks locks, Settings settings,
                            Supplier<ResourceCatalog> resources, Supplier<TechnologyCatalog> technologies,
                            ClaimEnvironmentPort environment, ConfirmationTokens confirmations, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.technologies = Objects.requireNonNull(technologies, "technologies");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.confirmations = Objects.requireNonNull(confirmations, "confirmations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Preparation prepareClaim(UUID actor, ClaimSite site) {
        OperationResult preflight = claimPreflight(actor, site);
        if (!preflight.success()) return Preparation.denied(preflight.message());
        StateSnapshot snapshot = cache.snapshot();
        Member member = snapshot.member(actor);
        int used = snapshot.claims(member.civilizationId()).size();
        int maximum = capacity(member.civilizationId()).maximum();
        Map<ResourceKey, Long> cost = resources.get().claimCost(used + 1);
        String fingerprint = claimFingerprint(snapshot, member.civilizationId(), site, used, cost);
        return Preparation.ready(confirmations.issue(actor, CONFIRM_CLAIM, fingerprint),
            "Claiming this chunk will cost exactly " + ResourceText.cost(resources.get(), cost) + ". Territory usage will become "
                + (used + 1) + "/" + maximum + ".");
    }

    public CompletableFuture<OperationResult> claim(UUID actor, ClaimSite site, String explicitToken) {
        OperationResult preflight = claimPreflight(actor, site);
        if (!preflight.success()) return CompletableFuture.completedFuture(preflight);
        StateSnapshot snapshot = cache.snapshot();
        Member member = snapshot.member(actor);
        long civilizationId = member.civilizationId();
        int expectedClaims = snapshot.claims(civilizationId).size();
        Map<ResourceKey, Long> expectedCost = resources.get().claimCost(expectedClaims + 1);
        String fingerprint = claimFingerprint(snapshot, civilizationId, site, expectedClaims, expectedCost);
        if (!confirmations.consume(actor, CONFIRM_CLAIM, fingerprint, explicitToken)) {
            return completedDenied("That confirmation expired or the territory/cost changed. Run /civ claim again.");
        }
        return database.transaction(connection -> locks.withLock(civilizationId,
                () -> claimLocked(connection, civilizationId, actor, site, expectedClaims, expectedCost)))
            .thenCompose(this::refreshAfterMutation);
    }

    public Preparation prepareUnclaim(UUID actor, ChunkKey chunk) {
        StateSnapshot snapshot = cache.snapshot();
        if (!cache.ready()) return Preparation.denied("Territory data is still warming up.");
        Member member = snapshot.member(actor);
        Claim claim = snapshot.claim(chunk);
        if (member == null || !member.role().atLeast(Role.ADVISOR)) return Preparation.denied("Only leaders and advisors may unclaim land.");
        if (claim == null || claim.civilizationId() != member.civilizationId()) return Preparation.denied("Your civilization does not own this chunk.");
        if (claim.isCapital()) return Preparation.denied("The capital cannot be unclaimed.");
        if (claim.plotOwnerId() != null) return Preparation.denied("Private plot rights must end before this chunk can be unclaimed.");
        if (snapshot.warFor(member.civilizationId()) != null) return Preparation.denied("Territory cannot be changed during a campaign.");
        String fingerprint = claimFingerprint(claim);
        return Preparation.ready(confirmations.issue(actor, CONFIRM_UNCLAIM, fingerprint),
            "Confirm releasing this chunk. Only half of its recorded civic cost is returned.");
    }

    public CompletableFuture<OperationResult> unclaim(UUID actor, ChunkKey chunk, String explicitToken) {
        if (!cache.ready()) return completedDenied("Territory data is still warming up.");
        Claim cached = cache.snapshot().claim(chunk);
        if (cached == null || !confirmations.consume(actor, CONFIRM_UNCLAIM, claimFingerprint(cached), explicitToken)) {
            return completedDenied("That confirmation token is invalid, expired, or the claim changed.");
        }
        Member member = cache.snapshot().member(actor);
        if (member == null) return completedDenied("You do not belong to a civilization.");
        return database.transaction(connection -> locks.withLock(member.civilizationId(),
                () -> unclaimLocked(connection, member.civilizationId(), actor, chunk, cached.rowVersion())))
            .thenCompose(this::refreshAfterMutation);
    }

    public Preparation prepareCapitalMove(UUID actor, ChunkKey chunk) {
        StateSnapshot snapshot = cache.snapshot();
        if (!cache.ready()) return Preparation.denied("Territory data is still warming up.");
        Member member = snapshot.member(actor);
        Claim claim = snapshot.claim(chunk);
        if (member == null || member.role() != Role.LEADER) return Preparation.denied("Only the leader may move the capital.");
        if (claim == null || claim.civilizationId() != member.civilizationId() || claim.plotType() != PlotType.CIVIC) {
            return Preparation.denied("The new capital must be an unlisted civic claim.");
        }
        var civilization = snapshot.civilization(member.civilizationId());
        String fingerprint = claimFingerprint(claim) + ":" + civilization.rowVersion();
        return Preparation.ready(confirmations.issue(actor, CONFIRM_CAPITAL, fingerprint),
            "Confirm moving the capital. It will consume exactly "
                + ResourceText.cost(resources.get(), settings.claims().capitalMoveCost()) + ".");
    }

    public CompletableFuture<OperationResult> moveCapital(UUID actor, ChunkKey chunk, HomeLocation newHome,
                                                           String explicitToken) {
        if (!cache.ready()) return completedDenied("Territory data is still warming up.");
        if (newHome == null || !finite(newHome)) return completedDenied("The proposed capital home is invalid.");
        ChunkKey homeChunk = new ChunkKey(newHome.worldId(), Math.floorDiv((int) Math.floor(newHome.x()), 16),
            Math.floorDiv((int) Math.floor(newHome.z()), 16));
        if (!homeChunk.equals(chunk)) return completedDenied("Stand inside the proposed capital when confirming the move.");
        StateSnapshot snapshot = cache.snapshot();
        Member member = snapshot.member(actor);
        Claim claim = snapshot.claim(chunk);
        if (member == null || claim == null) return completedDenied("The selected capital claim no longer exists.");
        var civilization = snapshot.civilization(member.civilizationId());
        String fingerprint = claimFingerprint(claim) + ":" + civilization.rowVersion();
        if (!confirmations.consume(actor, CONFIRM_CAPITAL, fingerprint, explicitToken)) {
            return completedDenied("That confirmation token is invalid, expired, or the territory changed.");
        }
        return database.transaction(connection -> locks.withLock(member.civilizationId(),
                () -> moveCapitalLocked(connection, member.civilizationId(), actor, chunk, newHome, claim.rowVersion())))
            .thenCompose(this::refreshAfterMutation);
    }

    public CompletableFuture<OperationResult> setHome(UUID actor, HomeLocation home) {
        if (!cache.ready()) return completedDenied("Territory data is still warming up.");
        Member member = cache.snapshot().member(actor);
        if (member == null) return completedDenied("You do not belong to a civilization.");
        return database.transaction(connection -> locks.withLock(member.civilizationId(),
                () -> setHomeLocked(connection, member.civilizationId(), actor, home)))
            .thenCompose(this::refreshAfterMutation);
    }

    /**
     * Final war-resolution primitive. It requires a secured objective, locks both
     * civilization rows in numeric order, rechecks both geometries, and terminates
     * all private rights before changing sovereignty.
     */
    public CompletableFuture<OperationResult> transferConqueredClaim(long warId, long claimId,
                                                                      long winnerCivilizationId, UUID actor) {
        Claim cached = cache.snapshot().claims().values().stream().filter(claim -> claim.id() == claimId).findFirst().orElse(null);
        if (!cache.ready() || cached == null) return completedDenied("The conquest target is not available in the territory cache.");
        long loserCivilizationId = cached.civilizationId();
        return database.transaction(connection -> locks.withLocks(loserCivilizationId, winnerCivilizationId,
                () -> conquerLocked(connection, warId, claimId, loserCivilizationId, winnerCivilizationId, actor)))
            .thenCompose(this::refreshAfterMutation);
    }

    public Capacity capacity(long civilizationId) {
        StateSnapshot snapshot = cache.snapshot();
        int maximum = TerritoryRules.claimCapacity(snapshot, civilizationId, settings.claims(), technologies.get());
        return new Capacity(snapshot.claims(civilizationId).size(), maximum);
    }

    /** Bounded local map. Every lookup is a single immutable-map lookup. */
    public MapView map(UUID viewer, ChunkKey center, int requestedRadius) {
        int radius = Math.max(1, Math.min(10, requestedRadius));
        StateSnapshot snapshot = cache.snapshot();
        Member viewerMembership = snapshot.member(viewer);
        Long ownCivilization = viewerMembership == null ? null : viewerMembership.civilizationId();
        List<MapCell> cells = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (int z = center.z() - radius; z <= center.z() + radius; z++) {
            for (int x = center.x() - radius; x <= center.x() + radius; x++) {
                ChunkKey key = new ChunkKey(center.worldId(), x, z);
                Claim claim = snapshot.claim(key);
                boolean current = key.equals(center);
                if (claim == null) {
                    cells.add(new MapCell(key, current, null, null, '.', "Wilderness"));
                    continue;
                }
                var civilization = snapshot.civilization(claim.civilizationId());
                boolean own = Objects.equals(ownCivilization, claim.civilizationId());
                char marker = own ? ownMarker(claim.plotType()) : 'X';
                cells.add(new MapCell(key, current, claim.civilizationId(), claim.plotType(), marker,
                    civilization == null ? "Unknown civilization" : civilization.name()));
            }
        }
        return new MapView(center, radius, cache.ready(), List.copyOf(cells));
    }

    public Inspection inspect(UUID viewer, ChunkKey chunk) {
        StateSnapshot snapshot = cache.snapshot();
        Claim claim = snapshot.claim(chunk);
        if (claim == null) return new Inspection(chunk, cache.ready(), false, null, null, null, null,
            "Wilderness", false);
        var civilization = snapshot.civilization(claim.civilizationId());
        Member membership = snapshot.member(viewer);
        boolean sameCivilization = membership != null && membership.civilizationId() == claim.civilizationId();
        String control = switch (claim.plotType()) {
            case CAPITAL -> "Capital administration";
            case CIVIC -> "Civic administration";
            case COMMON -> "All citizens";
            case FOR_SALE -> claim.listingPrice() == null ? "Listed plot" : "For sale: " + claim.listingPrice().toPlainString();
            case PRIVATE -> "Private tenure";
        };
        return new Inspection(chunk, cache.ready(), true, claim.id(), claim.civilizationId(),
            civilization == null ? null : civilization.name(), claim.plotType(), control, sameCivilization);
    }

    public boolean conquestSafe(Claim target, long conqueringCivilizationId) {
        return cache.ready() && TerritoryRules.conquestTransferKeepsBothTerritoriesValid(
            cache.snapshot(), target, conqueringCivilizationId);
    }

    private OperationResult claimPreflight(UUID actor, ClaimSite site) {
        if (!cache.ready()) return OperationResult.denied("Territory data is still warming up.");
        if (!settings.worlds().allowed(site.worldName())) return OperationResult.denied("Claims are disabled in this world.");
        if (settings.worlds().blacklistedBiomes().contains(site.biomeKey().toUpperCase(Locale.ROOT))) {
            return OperationResult.denied("This biome is blacklisted for claims.");
        }
        ClaimEnvironmentPort.Check environmentCheck = environment.check(actor, site.chunk(), site.worldName(), site.biomeKey());
        if (!environmentCheck.allowed()) return OperationResult.denied(environmentCheck.reason());
        StateSnapshot snapshot = cache.snapshot();
        Member member = snapshot.member(actor);
        if (member == null || !member.role().atLeast(Role.ADVISOR)) return OperationResult.denied("Only leaders and advisors may claim land.");
        if (snapshot.claim(site.chunk()) != null) return OperationResult.denied("This chunk is already claimed.");
        if (snapshot.warFor(member.civilizationId()) != null) return OperationResult.denied("Territory cannot expand during a campaign.");
        boolean adjacent = snapshot.claims(member.civilizationId()).stream().anyMatch(claim -> claim.key().cardinallyAdjacent(site.chunk()));
        if (!adjacent) return OperationResult.denied("New claims must share a cardinal edge with your territory.");
        Capacity capacity = capacity(member.civilizationId());
        if (capacity.used() >= capacity.maximum()) return OperationResult.denied("Your civilization is at its claim capacity ("
            + capacity.used() + "/" + capacity.maximum() + "). Use /civ info for the exact capacity formula.");
        return OperationResult.ok("Claim preflight passed.");
    }

    private OperationResult claimLocked(Connection connection, long civilizationId, UUID actor, ClaimSite site,
                                        int expectedClaims, Map<ResourceKey, Long> expectedCost) throws Exception {
        LockedCivilization civilization = lockCivilization(connection, civilizationId);
        Role role = lockMemberRole(connection, civilizationId, actor);
        if (civilization == null || role == null || !role.atLeast(Role.ADVISOR)) return OperationResult.denied("You may no longer claim for this civilization.");
        if (civilization.currentWarId() != null) return OperationResult.denied("Territory cannot expand during a campaign.");
        if (lockClaimAt(connection, site.chunk()) != null) return OperationResult.denied("This chunk was claimed by someone else.");

        List<LockedClaim> claims = lockClaims(connection, civilizationId);
        if (claims.stream().noneMatch(claim -> claim.key().cardinallyAdjacent(site.chunk()))) {
            return OperationResult.denied("The claim is no longer cardinally adjacent.");
        }
        Set<String> unlocked = technologyKeys(connection, civilizationId);
        List<Member> members = capacityMembers(connection, civilizationId);
        int maximum = TerritoryRules.claimCapacity(members, unlocked, civilization.adminClaimBonus(),
            settings.claims(), technologies.get());
        if (claims.size() >= maximum) return OperationResult.denied("Your civilization is at its claim capacity ("
            + claims.size() + "/" + maximum + "). Use /civ info for the exact capacity formula.");

        Map<ResourceKey, Long> cost = resources.get().claimCost(claims.size() + 1);
        if (claims.size() != expectedClaims || !cost.equals(expectedCost)) {
            return OperationResult.denied("Territory changed before confirmation; run /civ claim again to review the exact cost.");
        }
        UUID operationId = UUID.randomUUID();
        if (!spendStockpile(connection, civilizationId, actor, operationId, cost, "CLAIM", "CHUNK", site.chunk().compact())) {
            return OperationResult.denied("The civic stockpile cannot pay the exact next-claim cost: "
                + ResourceText.cost(resources.get(), cost) + ".");
        }
        Instant now = clock.instant();
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO civ_claims(world_uuid, world_name, chunk_x, chunk_z, civ_id, plot_type,
                acquisition_source, claim_cost_snapshot, claimed_at, claimed_by)
            VALUES (?, ?, ?, ?, ?, 'CIVIC', 'EXPANSION', ?, ?, ?)
            """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setBytes(1, UuidBytes.toBytes(site.chunk().worldId()));
            statement.setString(2, site.worldName());
            statement.setInt(3, site.chunk().x());
            statement.setInt(4, site.chunk().z());
            statement.setLong(5, civilizationId);
            statement.setString(6, JsonData.costs(cost));
            statement.setTimestamp(7, Timestamp.from(now));
            statement.setBytes(8, UuidBytes.toBytes(actor));
            try {
                statement.executeUpdate();
            } catch (SQLException conflict) {
                // Returning a denial here would commit the material spend above.
                // Throwing rolls the entire transaction back; the unique index is
                // the final cross-civilization race guard.
                if ("23000".equals(conflict.getSQLState())) {
                    throw new SQLException("This chunk was claimed concurrently", conflict.getSQLState(), conflict);
                }
                throw conflict;
            }
        }
        AuditLog.write(connection, civilizationId, actor, "CLAIM_CREATED", "CHUNK", site.chunk().compact(),
            Map.of("resulting_claims", claims.size() + 1, "cost", JsonData.costs(cost)), settings.serverId());
        return OperationResult.ok("Claim created for " + ResourceText.cost(resources.get(), cost) + ". Territory usage is now "
            + (claims.size() + 1) + "/" + maximum + ".");
    }

    private OperationResult unclaimLocked(Connection connection, long civilizationId, UUID actor, ChunkKey chunk,
                                          long expectedVersion) throws Exception {
        LockedCivilization civilization = lockCivilization(connection, civilizationId);
        Role role = lockMemberRole(connection, civilizationId, actor);
        if (civilization == null || role == null || !role.atLeast(Role.ADVISOR)) return OperationResult.denied("You may no longer unclaim for this civilization.");
        if (civilization.currentWarId() != null) return OperationResult.denied("Territory cannot change during a campaign.");
        LockedClaim target = lockClaimAt(connection, chunk);
        if (target == null || target.civilizationId() != civilizationId || target.rowVersion() != expectedVersion) {
            return OperationResult.denied("The claim changed before confirmation.");
        }
        if (target.type() == PlotType.CAPITAL) return OperationResult.denied("The capital cannot be unclaimed.");
        if (target.owner() != null) return OperationResult.denied("Private plot rights must end first.");
        if (isCampaignObjective(connection, target.id())) return OperationResult.denied("A campaign objective protects this chunk from unclaiming.");
        if (isPurchasePending(connection, target.id())) return OperationResult.denied("A plot purchase is already being processed in this chunk.");
        List<LockedClaim> claims = lockClaims(connection, civilizationId);
        Set<ChunkKey> keys = new HashSet<>();
        claims.forEach(claim -> keys.add(claim.key()));
        if (!Connectivity.remainsConnectedAfterRemoval(keys, civilization.capital(), chunk)) {
            return OperationResult.denied("Removing this chunk would disconnect territory from the capital.");
        }
        UUID operationId = UUID.randomUUID();
        Map<ResourceKey, Long> refund = percentage(target.cost(), settings.claims().refundPercent());
        addStockpile(connection, civilizationId, actor, operationId, refund, "UNCLAIM_REFUND", "CLAIM", Long.toString(target.id()));
        // Preserve terminal economy history without letting its FK prevent a
        // legitimate unclaim. Nonterminal operations were rejected above.
        try (PreparedStatement statement = Sql.prepare(connection, """
            UPDATE economy_operations SET claim_id = NULL, updated_at = ?
            WHERE claim_id = ? AND operation_type = 'PLOT_PURCHASE' AND state IN ('COMPLETED','FAILED')
            """, clock.instant(), target.id())) {
            statement.executeUpdate();
        }
        try (PreparedStatement statement = Sql.prepare(connection, "DELETE FROM civ_claims WHERE id = ? AND row_version = ?", target.id(), expectedVersion)) {
            if (statement.executeUpdate() != 1) return OperationResult.denied("The claim changed before it could be released.");
        }
        AuditLog.write(connection, civilizationId, actor, "CLAIM_RELEASED", "CLAIM", Long.toString(target.id()),
            Map.of("chunk", chunk.compact(), "refund", JsonData.costs(refund)), settings.serverId());
        return OperationResult.ok("Claim released; the configured partial civic refund was returned.");
    }

    private OperationResult moveCapitalLocked(Connection connection, long civilizationId, UUID actor, ChunkKey chunk,
                                               HomeLocation newHome, long expectedClaimVersion) throws Exception {
        LockedCivilization civilization = lockCivilization(connection, civilizationId);
        Role role = lockMemberRole(connection, civilizationId, actor);
        if (civilization == null || role != Role.LEADER) return OperationResult.denied("Only the current leader may move the capital.");
        if (civilization.currentWarId() != null) return OperationResult.denied("The capital cannot move during a campaign.");
        if (civilization.capitalMovedAt() != null
            && clock.instant().isBefore(civilization.capitalMovedAt().plus(settings.claims().capitalMoveCooldown()))) {
            return OperationResult.denied("The capital move cooldown has not expired.");
        }
        LockedClaim target = lockClaimAt(connection, chunk);
        if (target == null || target.civilizationId() != civilizationId || target.type() != PlotType.CIVIC
            || target.rowVersion() != expectedClaimVersion) {
            return OperationResult.denied("The new capital must still be an unlisted civic claim.");
        }
        if (isCampaignObjective(connection, target.id())) return OperationResult.denied("A campaign objective cannot become the capital.");
        List<LockedClaim> claims = lockClaims(connection, civilizationId);
        int maximum = TerritoryRules.claimCapacity(capacityMembers(connection, civilizationId),
            technologyKeys(connection, civilizationId), civilization.adminClaimBonus(), settings.claims(), technologies.get());
        if (claims.size() > maximum) return OperationResult.denied("The civilization is over capacity and cannot move its capital.");
        Set<ChunkKey> keys = new HashSet<>();
        claims.forEach(claim -> keys.add(claim.key()));
        if (!Connectivity.allConnected(keys, chunk)) return OperationResult.denied("The territory is not connected to the proposed capital.");
        UUID operationId = UUID.randomUUID();
        if (!spendStockpile(connection, civilizationId, actor, operationId, settings.claims().capitalMoveCost(),
            "CAPITAL_MOVE", "CLAIM", Long.toString(target.id()))) {
            return OperationResult.denied("The civic stockpile cannot pay the capital move cost.");
        }
        try (PreparedStatement oldCapital = Sql.prepare(connection,
                 "UPDATE civ_claims SET plot_type = 'CIVIC', row_version = row_version + 1 WHERE civ_id = ? AND plot_type = 'CAPITAL'", civilizationId);
             PreparedStatement newCapital = Sql.prepare(connection,
                 "UPDATE civ_claims SET plot_type = 'CAPITAL', row_version = row_version + 1 WHERE id = ? AND row_version = ?",
                 target.id(), expectedClaimVersion)) {
            if (oldCapital.executeUpdate() != 1 || newCapital.executeUpdate() != 1) {
                throw new SQLException("Capital claim invariant failed");
            }
        }
        Instant now = clock.instant();
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE civilizations SET capital_world_uuid = ?, capital_world_name = ?, capital_chunk_x = ?, capital_chunk_z = ?,
                home_x = ?, home_y = ?, home_z = ?, home_yaw = ?, home_pitch = ?,
                capital_moved_at = ?, row_version = row_version + 1 WHERE id = ?
            """)) {
            statement.setBytes(1, UuidBytes.toBytes(chunk.worldId()));
            statement.setString(2, target.worldName());
            statement.setInt(3, chunk.x());
            statement.setInt(4, chunk.z());
            statement.setDouble(5, newHome.x());
            statement.setDouble(6, newHome.y());
            statement.setDouble(7, newHome.z());
            statement.setFloat(8, newHome.yaw());
            statement.setFloat(9, newHome.pitch());
            statement.setTimestamp(10, Timestamp.from(now));
            statement.setLong(11, civilizationId);
            statement.executeUpdate();
        }
        AuditLog.write(connection, civilizationId, actor, "CAPITAL_MOVED", "CLAIM", Long.toString(target.id()),
            Map.of("old", civilization.capital().compact(), "new", chunk.compact()), settings.serverId());
        return OperationResult.ok("The civilization capital has moved to this claim.");
    }

    private OperationResult setHomeLocked(Connection connection, long civilizationId, UUID actor, HomeLocation home) throws Exception {
        LockedCivilization civilization = lockCivilization(connection, civilizationId);
        Role role = lockMemberRole(connection, civilizationId, actor);
        if (civilization == null || role == null || !role.atLeast(Role.ADVISOR)) return OperationResult.denied("Only leaders and advisors may set the civilization home.");
        if (!finite(home)) return OperationResult.denied("The home coordinates are invalid.");
        ChunkKey homeChunk = new ChunkKey(home.worldId(), Math.floorDiv((int) Math.floor(home.x()), 16),
            Math.floorDiv((int) Math.floor(home.z()), 16));
        if (!homeChunk.equals(civilization.capital())) return OperationResult.denied("The civilization home must be inside the capital chunk.");
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE civilizations SET home_x = ?, home_y = ?, home_z = ?, home_yaw = ?, home_pitch = ?,
                row_version = row_version + 1 WHERE id = ?
            """)) {
            statement.setDouble(1, home.x());
            statement.setDouble(2, home.y());
            statement.setDouble(3, home.z());
            statement.setFloat(4, home.yaw());
            statement.setFloat(5, home.pitch());
            statement.setLong(6, civilizationId);
            statement.executeUpdate();
        }
        AuditLog.write(connection, civilizationId, actor, "CIVILIZATION_HOME_SET", "CHUNK", homeChunk.compact(),
            Map.of("x", home.x(), "y", home.y(), "z", home.z()), settings.serverId());
        return OperationResult.ok("Civilization home updated.");
    }

    private OperationResult conquerLocked(Connection connection, long warId, long claimId, long expectedLoser,
                                           long winner, UUID actor) throws Exception {
        LockedCivilization first = lockCivilization(connection, Math.min(expectedLoser, winner));
        LockedCivilization second = lockCivilization(connection, Math.max(expectedLoser, winner));
        if (first == null || second == null) return OperationResult.denied("A campaign civilization no longer exists.");
        LockedClaim target = lockClaimById(connection, claimId);
        if (target == null || target.civilizationId() != expectedLoser || target.type() == PlotType.CAPITAL) {
            return OperationResult.denied("The conquest target is no longer transferable.");
        }
        if (!securedObjective(connection, warId, claimId, expectedLoser, winner)) {
            return OperationResult.denied("Only a secured objective in this campaign may transfer.");
        }
        List<LockedClaim> defenderClaims = lockClaims(connection, expectedLoser);
        List<LockedClaim> winnerClaims = lockClaims(connection, winner);
        LockedCivilization defender = first.id() == expectedLoser ? first : second;
        Set<ChunkKey> defenderKeys = new HashSet<>();
        defenderClaims.forEach(claim -> defenderKeys.add(claim.key()));
        if (!Connectivity.remainsConnectedAfterRemoval(defenderKeys, defender.capital(), target.key())) {
            return OperationResult.denied("The transfer would disconnect the defender from its capital.");
        }
        if (winnerClaims.stream().noneMatch(claim -> claim.key().cardinallyAdjacent(target.key()))) {
            return OperationResult.denied("The transfer would not connect to the conquering territory.");
        }
        try (PreparedStatement trust = Sql.prepare(connection, "DELETE FROM plot_trust WHERE claim_id = ?", claimId);
             PreparedStatement update = Sql.prepare(connection, """
                 UPDATE civ_claims SET civ_id = ?, plot_type = 'CIVIC', plot_owner_uuid = NULL,
                     listing_kind = NULL, listing_seller_uuid = NULL, listing_price = NULL,
                     original_purchase_price = NULL, listed_at = NULL, purchased_at = NULL, purchased_by = NULL,
                     plot_flags = NULL, home_label = NULL, greeting = NULL,
                     acquisition_source = 'CONQUEST', row_version = row_version + 1 WHERE id = ?
                 """, winner, claimId)) {
            trust.executeUpdate();
            if (update.executeUpdate() != 1) throw new SQLException("Conquest claim update failed");
        }
        AuditLog.write(connection, expectedLoser, actor, "CLAIM_CONQUERED_AWAY", "CLAIM", Long.toString(claimId),
            Map.of("war", warId, "winner", winner, "private_rights_terminated", target.owner() != null), settings.serverId());
        AuditLog.write(connection, winner, actor, "CLAIM_CONQUERED", "CLAIM", Long.toString(claimId),
            Map.of("war", warId, "former_owner", expectedLoser, "chunk", target.key().compact()), settings.serverId());
        return OperationResult.ok("The secured claim transferred and all former private rights ended.");
    }

    private LockedCivilization lockCivilization(Connection connection, long civilizationId) throws SQLException {
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT id, capital_world_uuid, capital_chunk_x, capital_chunk_z, current_war_id,
                   admin_claim_bonus, capital_moved_at, row_version
            FROM civilizations WHERE id = ? AND status = 'ACTIVE' FOR UPDATE
            """, civilizationId); ResultSet result = statement.executeQuery()) {
            if (!result.next()) return null;
            long war = result.getLong("current_war_id");
            Long currentWar = result.wasNull() ? null : war;
            return new LockedCivilization(result.getLong("id"),
                new ChunkKey(UuidBytes.get(result, "capital_world_uuid"), result.getInt("capital_chunk_x"), result.getInt("capital_chunk_z")),
                currentWar, result.getInt("admin_claim_bonus"),
                result.getTimestamp("capital_moved_at") == null ? null : result.getTimestamp("capital_moved_at").toInstant(),
                result.getLong("row_version"));
        }
    }

    private Role lockMemberRole(Connection connection, long civilizationId, UUID actor) throws SQLException {
        try (PreparedStatement statement = Sql.prepare(connection,
            "SELECT role FROM civ_members WHERE civ_id = ? AND player_uuid = ? FOR UPDATE", civilizationId, actor);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? Role.valueOf(result.getString(1)) : null;
        }
    }

    private LockedClaim lockClaimAt(Connection connection, ChunkKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, world_uuid, world_name, chunk_x, chunk_z, civ_id, plot_type, plot_owner_uuid,
                   claim_cost_snapshot, row_version FROM civ_claims
            WHERE world_uuid = ? AND chunk_x = ? AND chunk_z = ? FOR UPDATE
            """)) {
            statement.setBytes(1, UuidBytes.toBytes(key.worldId()));
            statement.setInt(2, key.x());
            statement.setInt(3, key.z());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readLockedClaim(result) : null;
            }
        }
    }

    private LockedClaim lockClaimById(Connection connection, long claimId) throws SQLException {
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT id, world_uuid, world_name, chunk_x, chunk_z, civ_id, plot_type, plot_owner_uuid,
                   claim_cost_snapshot, row_version FROM civ_claims WHERE id = ? FOR UPDATE
            """, claimId); ResultSet result = statement.executeQuery()) {
            return result.next() ? readLockedClaim(result) : null;
        }
    }

    private List<LockedClaim> lockClaims(Connection connection, long civilizationId) throws SQLException {
        List<LockedClaim> values = new ArrayList<>();
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT id, world_uuid, world_name, chunk_x, chunk_z, civ_id, plot_type, plot_owner_uuid,
                   claim_cost_snapshot, row_version FROM civ_claims WHERE civ_id = ? ORDER BY id FOR UPDATE
            """, civilizationId); ResultSet result = statement.executeQuery()) {
            while (result.next()) values.add(readLockedClaim(result));
        }
        return values;
    }

    private LockedClaim readLockedClaim(ResultSet result) throws SQLException {
        return new LockedClaim(result.getLong("id"),
            new ChunkKey(UuidBytes.get(result, "world_uuid"), result.getInt("chunk_x"), result.getInt("chunk_z")),
            result.getString("world_name"), result.getLong("civ_id"), PlotType.valueOf(result.getString("plot_type")),
            UuidBytes.get(result, "plot_owner_uuid"), JsonData.costs(result.getString("claim_cost_snapshot")),
            result.getLong("row_version"));
    }

    private List<Member> capacityMembers(Connection connection, long civilizationId) throws SQLException {
        List<Member> values = new ArrayList<>();
        try (PreparedStatement statement = Sql.prepare(connection,
            "SELECT player_uuid, role, established FROM civ_members WHERE civ_id = ?", civilizationId);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) values.add(new Member(civilizationId, UuidBytes.get(result, "player_uuid"), "",
                Role.valueOf(result.getString("role")), Instant.EPOCH, Instant.EPOCH, 0,
                result.getBoolean("established"), 0, false));
        }
        return values;
    }

    private Set<String> technologyKeys(Connection connection, long civilizationId) throws SQLException {
        Set<String> values = new LinkedHashSet<>();
        try (PreparedStatement statement = Sql.prepare(connection,
            "SELECT technology_key FROM civ_technologies WHERE civ_id = ?", civilizationId);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) values.add(result.getString(1));
        }
        return Set.copyOf(values);
    }

    private boolean spendStockpile(Connection connection, long civilizationId, UUID actor, UUID operationId,
                                   Map<ResourceKey, Long> costs, String reason, String relatedType,
                                   String relatedId) throws SQLException {
        List<Map.Entry<ResourceKey, Long>> ordered = costs.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        Map<ResourceKey, Long> balances = lockStockpile(connection, civilizationId, ordered.stream().map(Map.Entry::getKey).toList());
        for (Map.Entry<ResourceKey, Long> entry : ordered) {
            if (balances.getOrDefault(entry.getKey(), 0L) < entry.getValue()) return false;
        }
        for (Map.Entry<ResourceKey, Long> entry : ordered) {
            try (PreparedStatement update = Sql.prepare(connection, """
                     UPDATE civ_stockpile SET quantity = quantity - ?
                     WHERE civ_id = ? AND resource_key = ? AND tier = ? AND quantity >= ?
                     """, entry.getValue(), civilizationId, entry.getKey().family(), entry.getKey().tier(), entry.getValue())) {
                if (update.executeUpdate() != 1) throw new SQLException("Stockpile changed during locked spend");
            }
            writeStockpileLedger(connection, operationId, civilizationId, actor, entry.getKey(), -entry.getValue(), reason, relatedType, relatedId);
        }
        return true;
    }

    private void addStockpile(Connection connection, long civilizationId, UUID actor, UUID operationId,
                              Map<ResourceKey, Long> amounts, String reason, String relatedType,
                              String relatedId) throws SQLException {
        List<Map.Entry<ResourceKey, Long>> ordered = amounts.entrySet().stream().filter(entry -> entry.getValue() > 0)
            .sorted(Map.Entry.comparingByKey()).toList();
        lockStockpile(connection, civilizationId, ordered.stream().map(Map.Entry::getKey).toList());
        for (Map.Entry<ResourceKey, Long> entry : ordered) {
            try (PreparedStatement update = Sql.prepare(connection, """
                UPDATE civ_stockpile SET quantity = quantity + ? WHERE civ_id = ? AND resource_key = ? AND tier = ?
                """, entry.getValue(), civilizationId, entry.getKey().family(), entry.getKey().tier())) {
                update.executeUpdate();
            }
            writeStockpileLedger(connection, operationId, civilizationId, actor, entry.getKey(), entry.getValue(), reason, relatedType, relatedId);
        }
    }

    private Map<ResourceKey, Long> lockStockpile(Connection connection, long civilizationId,
                                                  List<ResourceKey> keys) throws SQLException {
        Map<ResourceKey, Long> values = new HashMap<>();
        for (ResourceKey key : keys.stream().distinct().sorted().toList()) {
            try (PreparedStatement ensure = Sql.prepare(connection, """
                INSERT INTO civ_stockpile(civ_id, resource_key, tier, quantity) VALUES (?, ?, ?, 0)
                ON DUPLICATE KEY UPDATE quantity = quantity
                """, civilizationId, key.family(), key.tier())) {
                ensure.executeUpdate();
            }
            try (PreparedStatement select = Sql.prepare(connection, """
                SELECT quantity FROM civ_stockpile WHERE civ_id = ? AND resource_key = ? AND tier = ? FOR UPDATE
                """, civilizationId, key.family(), key.tier()); ResultSet result = select.executeQuery()) {
                if (!result.next()) throw new SQLException("Unable to lock stockpile row");
                values.put(key, result.getLong(1));
            }
        }
        return values;
    }

    private void writeStockpileLedger(Connection connection, UUID operationId, long civilizationId, UUID actor,
                                      ResourceKey key, long delta, String reason, String relatedType,
                                      String relatedId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO stockpile_ledger(operation_id, civ_id, actor_uuid, resource_key, tier, delta,
                reason, related_type, related_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setBytes(1, UuidBytes.toBytes(operationId));
            statement.setLong(2, civilizationId);
            statement.setBytes(3, UuidBytes.toBytes(actor));
            statement.setString(4, key.family());
            statement.setInt(5, key.tier());
            statement.setLong(6, delta);
            statement.setString(7, reason);
            statement.setString(8, relatedType);
            statement.setString(9, relatedId);
            statement.setTimestamp(10, Timestamp.from(clock.instant()));
            statement.executeUpdate();
        }
    }

    private boolean isCampaignObjective(Connection connection, long claimId) throws SQLException {
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT 1 FROM war_objectives o JOIN wars w ON w.id = o.war_id
            WHERE o.target_claim_id = ? AND w.state IN ('PENDING','ACTIVE','RESOLVING') LIMIT 1 FOR UPDATE
            """, claimId); ResultSet result = statement.executeQuery()) {
            return result.next();
        }
    }

    private boolean isPurchasePending(Connection connection, long claimId) throws SQLException {
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT 1 FROM economy_operations WHERE claim_id = ? AND operation_type = 'PLOT_PURCHASE'
              AND state IN ('PENDING','WITHDRAWAL_IN_FLIGHT','EXTERNAL_APPLIED','DB_APPLIED',
                            'DELIVERY_IN_FLIGHT','COMPENSATION_PENDING','REFUND_IN_FLIGHT','PENDING_DELIVERY')
            LIMIT 1 FOR UPDATE
            """, claimId); ResultSet result = statement.executeQuery()) {
            return result.next();
        }
    }

    private boolean securedObjective(Connection connection, long warId, long claimId, long loser, long winner) throws SQLException {
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT 1 FROM war_objectives o JOIN wars w ON w.id = o.war_id
            WHERE o.war_id = ? AND o.target_claim_id = ? AND o.nominating_civ_id = ?
              AND o.state = 'SECURED' AND w.state IN ('ACTIVE','RESOLVING')
              AND ((w.attacker_civ_id = ? AND w.defender_civ_id = ?) OR (w.attacker_civ_id = ? AND w.defender_civ_id = ?))
            FOR UPDATE
            """, warId, claimId, winner, winner, loser, loser, winner); ResultSet result = statement.executeQuery()) {
            return result.next();
        }
    }

    private CompletableFuture<OperationResult> refreshAfterMutation(OperationResult result) {
        if (!result.success()) return CompletableFuture.completedFuture(result);
        return cache.refreshAfterMutation().handle((ignored, failure) -> {
            if (failure == null) return result;
            return OperationResult.ok(result.message() + " Protection remains fail-closed until the cache reload succeeds.");
        });
    }

    private static CompletableFuture<OperationResult> completedDenied(String message) {
        return CompletableFuture.completedFuture(OperationResult.denied(message));
    }

    private static String claimFingerprint(Claim claim) {
        return claim.id() + ":" + claim.rowVersion() + ":" + claim.plotType() + ":" + claim.civilizationId();
    }

    private static String claimFingerprint(StateSnapshot snapshot, long civilizationId, ClaimSite site,
                                           int claimCount, Map<ResourceKey, Long> cost) {
        var civilization = snapshot.civilization(civilizationId);
        long version = civilization == null ? -1L : civilization.rowVersion();
        java.util.TreeMap<String, Long> orderedCost = new java.util.TreeMap<>();
        cost.forEach((key, value) -> orderedCost.put(key.serialized(), value));
        return site.chunk().compact() + ":" + civilizationId + ":" + version + ":" + claimCount + ":" + orderedCost;
    }

    private static boolean finite(HomeLocation home) {
        return home != null && home.worldId() != null && Double.isFinite(home.x()) && Double.isFinite(home.y())
            && Double.isFinite(home.z()) && Float.isFinite(home.yaw()) && Float.isFinite(home.pitch());
    }

    private static Map<ResourceKey, Long> percentage(Map<ResourceKey, Long> source, int percent) {
        Map<ResourceKey, Long> result = new LinkedHashMap<>();
        source.forEach((key, amount) -> {
            long value = (amount / 100L) * percent + ((amount % 100L) * percent) / 100L;
            if (value > 0) result.put(key, value);
        });
        return Map.copyOf(result);
    }

    private static char ownMarker(PlotType type) {
        return switch (type) {
            case CAPITAL -> 'K';
            case CIVIC -> 'C';
            case COMMON -> 'M';
            case FOR_SALE -> '$';
            case PRIVATE -> 'P';
        };
    }

    public record ClaimSite(ChunkKey chunk, String worldName, String biomeKey) {
        public ClaimSite {
            Objects.requireNonNull(chunk, "chunk");
            Objects.requireNonNull(worldName, "worldName");
            Objects.requireNonNull(biomeKey, "biomeKey");
        }
    }

    public record Capacity(int used, int maximum) {
        public boolean overCapacity() { return used > maximum; }
        public int remaining() { return Math.max(0, maximum - used); }
    }

    public record Preparation(OperationResult validation, ConfirmationTokens.Confirmation confirmation) {
        public static Preparation denied(String message) {
            return new Preparation(OperationResult.denied(message), null);
        }

        public static Preparation ready(ConfirmationTokens.Confirmation confirmation, String message) {
            return new Preparation(OperationResult.ok(message), confirmation);
        }
    }

    public record MapView(ChunkKey center, int radius, boolean cacheReady, List<MapCell> cells) {}

    public record MapCell(ChunkKey chunk, boolean current, Long civilizationId, PlotType plotType,
                          char marker, String description) {}

    public record Inspection(ChunkKey chunk, boolean cacheReady, boolean claimed, Long claimId,
                             Long civilizationId, String civilizationName, PlotType plotType,
                             String control, boolean ownCivilization) {}

    private record LockedCivilization(long id, ChunkKey capital, Long currentWarId, int adminClaimBonus,
                                      Instant capitalMovedAt, long rowVersion) {}

    private record LockedClaim(long id, ChunkKey key, String worldName, long civilizationId, PlotType type,
                               UUID owner, Map<ResourceKey, Long> cost, long rowVersion) {}
}
