package io.github.empireage.civilizations.service.war;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.concurrent.CivilizationLocks;
import io.github.empireage.civilizations.config.CatalogManager;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.config.ResourceText;
import io.github.empireage.civilizations.database.AuditLog;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.database.JsonData;
import io.github.empireage.civilizations.domain.Claim;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.Civilization;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.domain.PlotType;
import io.github.empireage.civilizations.domain.ResourceKey;
import io.github.empireage.civilizations.domain.Role;
import io.github.empireage.civilizations.domain.War;
import io.github.empireage.civilizations.domain.WarState;
import io.github.empireage.civilizations.service.progression.StockpileService;
import io.github.empireage.civilizations.util.TimeUtil;
import io.github.empireage.civilizations.util.UuidBytes;
import io.github.empireage.civilizations.util.WarSchedule;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class WarService {
    private final Database database;
    private final StateCache cache;
    private final Settings settings;
    private final CatalogManager catalogs;
    private final StockpileService stockpile;
    private final CivilizationLocks locks;
    private final Clock clock;

    public WarService(Database database, StateCache cache, Settings settings, CatalogManager catalogs,
                      StockpileService stockpile, CivilizationLocks locks) {
        this(database, cache, settings, catalogs, stockpile, locks, Clock.systemUTC());
    }

    WarService(Database database, StateCache cache, Settings settings, CatalogManager catalogs,
               StockpileService stockpile, CivilizationLocks locks, Clock clock) {
        this.database = database;
        this.cache = cache;
        this.settings = settings;
        this.catalogs = catalogs;
        this.stockpile = stockpile;
        this.locks = locks;
        this.clock = clock;
    }

    public CompletableFuture<OperationResult> declare(UUID actorId, String targetName, boolean charterRemoved) {
        if (!charterRemoved) return CompletableFuture.completedFuture(OperationResult.denied("A genuine War Charter is required."));
        StateSnapshot state = cache.snapshot();
        Member actor = state.member(actorId);
        if (actor == null || actor.role() != Role.LEADER) {
            return CompletableFuture.completedFuture(OperationResult.denied("Only a civilization leader may declare war."));
        }
        Civilization target = state.civilization(targetName);
        if (target == null) return CompletableFuture.completedFuture(OperationResult.denied("No active civilization matches that name."));
        if (target.id() == actor.civilizationId()) return CompletableFuture.completedFuture(OperationResult.denied("A civilization cannot declare war on itself."));
        Civilization attacker = state.civilization(actor.civilizationId());
        Instant now = clock.instant();
        WarSchedule.Window window = WarSchedule.nextWindow(now, settings.war().zone(), settings.war().weekday(),
            settings.war().start(), settings.war().end(), settings.war().notice());
        return database.transaction(connection -> locks.withLocks(attacker.id(), target.id(), () ->
            declareTransaction(connection, actorId, attacker.id(), target.id(), now, window)))
            .thenCompose(result -> refreshAfterSuccess(result));
    }

    private OperationResult declareTransaction(Connection connection, UUID actorId, long attackerId, long defenderId,
                                                Instant now, WarSchedule.Window window) throws Exception {
        long first = Math.min(attackerId, defenderId);
        long second = Math.max(attackerId, defenderId);
        CivWarRow firstRow = lockCivilization(connection, first);
        CivWarRow secondRow = lockCivilization(connection, second);
        CivWarRow attacker = firstRow.id == attackerId ? firstRow : secondRow;
        CivWarRow defender = firstRow.id == defenderId ? firstRow : secondRow;
        if (attacker.currentWarId != null || defender.currentWarId != null) {
            return OperationResult.denied("Each civilization may participate in only one unresolved campaign at a time.");
        }
        if (attacker.peaceShieldUntil != null && now.isBefore(attacker.peaceShieldUntil)) {
            return OperationResult.denied("Your civilization is still protected by its founding peace shield.");
        }
        if (defender.peaceShieldUntil != null && now.isBefore(defender.peaceShieldUntil)) {
            return OperationResult.denied("The target civilization is still protected by its founding peace shield.");
        }
        int requiredMembers = settings.war().minimumEstablishedMembers();
        if (establishedCount(connection, attackerId) < requiredMembers || establishedCount(connection, defenderId) < requiredMembers) {
            return OperationResult.denied("Both civilizations need at least " + requiredMembers + " established members.");
        }
        if (!shareBorder(connection, attackerId, defenderId)) return OperationResult.denied("War requires a shared cardinal border.");
        if (inTruce(connection, attackerId, defenderId, now)) return OperationResult.denied("These civilizations are still in their post-campaign truce.");
        Map<ResourceKey, Long> costs = settings.war().declarationCost();
        OperationResult availability = verifyStockpile(connection, attackerId, costs);
        if (!availability.success()) return availability;

        UUID operationId = UUID.randomUUID();
        spendStockpile(connection, attackerId, actorId, costs, operationId, "WAR_DECLARATION", null);
        long warId;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO wars(attacker_civ_id, defender_civ_id, state, declaration_actor, declared_at,
                scheduled_start, scheduled_end, timezone_id, cancellation_grace_until, objective_lock_at,
                declaration_cost_snapshot, attacker_peace, defender_peace)
            VALUES (?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?, FALSE, FALSE)
            """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, attackerId);
            statement.setLong(2, defenderId);
            statement.setBytes(3, UuidBytes.toBytes(actorId));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setTimestamp(5, Timestamp.from(window.start()));
            statement.setTimestamp(6, Timestamp.from(window.end()));
            statement.setString(7, window.zoneId().getId());
            statement.setTimestamp(8, Timestamp.from(now.plus(settings.war().declarationGrace())));
            statement.setTimestamp(9, Timestamp.from(window.start()));
            statement.setString(10, JsonData.costs(costs));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new IllegalStateException("War insert returned no ID");
                warId = keys.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE civilizations SET current_war_id = ?, peace_shield_until = CASE WHEN id = ? THEN ? ELSE peace_shield_until END,
                row_version = row_version + 1 WHERE id IN (?, ?)
            """)) {
            statement.setLong(1, warId);
            statement.setLong(2, attackerId);
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setLong(4, attackerId);
            statement.setLong(5, defenderId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE civ_members SET membership_locked = TRUE WHERE civ_id IN (?, ?)")) {
            statement.setLong(1, attackerId);
            statement.setLong(2, defenderId);
            statement.executeUpdate();
        }
        AuditLog.write(connection, attackerId, actorId, "war.declare", "war", String.valueOf(warId),
            Map.of("defender", defenderId, "scheduledStart", window.start().toString(), "scheduledEnd", window.end().toString()), settings.serverId());
        AuditLog.write(connection, defenderId, actorId, "war.declared_against", "war", String.valueOf(warId),
            Map.of("attacker", attackerId), settings.serverId());
        return OperationResult.ok("Campaign #" + warId + " is scheduled for " + window.start().atZone(window.zoneId())
            + " (" + TimeUtil.relative(window.start(), now) + ").");
    }

    public CompletableFuture<OperationResult> cancel(UUID actorId) {
        StateSnapshot state = cache.snapshot();
        Member actor = state.member(actorId);
        if (actor == null || actor.role() != Role.LEADER) return CompletableFuture.completedFuture(
            OperationResult.denied("Only the attacking leader may cancel a declaration."));
        War war = state.warFor(actor.civilizationId());
        if (war == null || war.attackerCivilizationId() != actor.civilizationId()) return CompletableFuture.completedFuture(
            OperationResult.denied("Your civilization has no attack declaration to cancel."));
        return database.transaction(connection -> locks.withLocks(war.attackerCivilizationId(), war.defenderCivilizationId(), () -> {
            WarRow current = lockWar(connection, war.id());
            Instant now = clock.instant();
            if (current == null || current.state != WarState.PENDING || now.isAfter(current.graceUntil)) {
                return OperationResult.denied("The ten-minute declaration cancellation grace period has ended.");
            }
            Map<ResourceKey, Long> refund = current.declarationCost;
            refundStockpile(connection, current.attackerId, actorId, refund, UUID.randomUUID(), "WAR_CANCEL_REFUND", String.valueOf(war.id()));
            cancelWar(connection, current, actorId, "DECLARATION_GRACE");
            return OperationResult.ok("Campaign cancelled. Declaration materials were refunded; your War Charter was also returned.");
        })).thenCompose(this::refreshAfterSuccess);
    }

    public CompletableFuture<OperationResult> peace(UUID actorId) {
        StateSnapshot state = cache.snapshot();
        Member actor = state.member(actorId);
        if (actor == null || actor.role() != Role.LEADER) return CompletableFuture.completedFuture(
            OperationResult.denied("Only a leader may offer or accept peace."));
        War war = state.warFor(actor.civilizationId());
        if (war == null) return CompletableFuture.completedFuture(OperationResult.denied("Your civilization has no unresolved campaign."));
        return database.transaction(connection -> locks.withLocks(war.attackerCivilizationId(), war.defenderCivilizationId(), () -> {
            WarRow current = lockWar(connection, war.id());
            if (current == null || !current.state.current()) return OperationResult.denied("That campaign is already resolved.");
            String column = actor.civilizationId() == current.attackerId ? "attacker_peace" : "defender_peace";
            try (PreparedStatement statement = connection.prepareStatement("UPDATE wars SET " + column + " = TRUE, row_version = row_version + 1 WHERE id = ?")) {
                statement.setLong(1, current.id);
                statement.executeUpdate();
            }
            boolean otherAccepted = actor.civilizationId() == current.attackerId ? current.defenderPeace : current.attackerPeace;
            AuditLog.write(connection, actor.civilizationId(), actorId, "war.peace.offer", "war", String.valueOf(current.id), Map.of(), settings.serverId());
            if (!otherAccepted) return OperationResult.ok("Peace offered. The opposing leader must run /civ war peace to accept.");
            cancelWar(connection, current, actorId, "MUTUAL_PEACE");
            return OperationResult.ok("Both leaders accepted peace. The war window has ended.");
        })).thenCompose(this::refreshAfterSuccess);
    }

    public CompletableFuture<TransitionResult> reconcileTransitions() {
        if (!database.healthy()) return CompletableFuture.completedFuture(TransitionResult.empty());
        Instant now = clock.instant();
        return database.transaction(connection -> {
            Set<Long> activated = new HashSet<>();
            Set<Long> resolved = new HashSet<>();
            Set<Long> truceExpired = new HashSet<>();
            List<WarRow> currentWars = currentWarsForUpdate(connection);
            for (WarRow war : currentWars) {
                if (war.state == WarState.TRUCE && war.truceUntil != null && !now.isBefore(war.truceUntil)) {
                    updateWarState(connection, war.id, WarState.COMPLETED);
                    truceExpired.add(war.id);
                    continue;
                }
                if (war.state == WarState.PENDING && !now.isBefore(war.start) && now.isBefore(war.end)) {
                    activate(connection, war, now);
                    activated.add(war.id);
                    continue;
                }
                if ((war.state == WarState.PENDING || war.state == WarState.ACTIVE || war.state == WarState.RESOLVING)
                    && !now.isBefore(war.end)) {
                    resolve(connection, war, now);
                    resolved.add(war.id);
                }
            }
            return new TransitionResult(Set.copyOf(activated), Set.copyOf(resolved), Set.copyOf(truceExpired));
        }).thenCompose(result -> result.changed()
            ? cache.refreshAfterMutation().handle((ignored, refreshFailure) -> result)
            : CompletableFuture.completedFuture(result));
    }

    private void activate(Connection connection, WarRow war, Instant now) throws Exception {
        try (PreparedStatement roster = connection.prepareStatement("""
            INSERT IGNORE INTO war_roster(war_id, civ_id, player_uuid, role, eligible)
            SELECT ?, civ_id, player_uuid, role, TRUE FROM civ_members WHERE civ_id IN (?, ?)
            """)) {
            roster.setLong(1, war.id);
            roster.setLong(2, war.attackerId);
            roster.setLong(3, war.defenderId);
            roster.executeUpdate();
        }
        updateWarState(connection, war.id, WarState.ACTIVE);
        AuditLog.write(connection, war.attackerId, null, "war.activate", "war", String.valueOf(war.id), Map.of("at", now.toString()), settings.serverId());
        AuditLog.write(connection, war.defenderId, null, "war.activate", "war", String.valueOf(war.id), Map.of("at", now.toString()), settings.serverId());
    }

    private void resolve(Connection connection, WarRow war, Instant now) throws Exception {
        // Keep legacy objectives for history/standard cleanup, but never transfer land or stockpiles.
        try (PreparedStatement objectives = connection.prepareStatement("""
            UPDATE war_objectives SET state = 'FAILED', resolved_at = ?, row_version = row_version + 1
            WHERE war_id = ? AND state NOT IN ('FAILED', 'TRANSFERRED')
            """)) {
            objectives.setTimestamp(1, Timestamp.from(now));
            objectives.setLong(2, war.id);
            objectives.executeUpdate();
        }
        // Downtime must not extend the truce beyond the persisted window boundary.
        Instant truceUntil = war.end.plus(settings.war().truce());
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE wars SET state = 'TRUCE', truce_until = ?, resolved_at = ?, result = 'WINDOW_ENDED',
                winning_civ_id = NULL, resolution_metadata = NULL, row_version = row_version + 1 WHERE id = ?
            """)) {
            update.setTimestamp(1, Timestamp.from(truceUntil));
            update.setTimestamp(2, Timestamp.from(now));
            update.setLong(3, war.id);
            update.executeUpdate();
        }
        clearWarLocks(connection, war);
        Map<String, Object> resolution = Map.of("truceUntil", truceUntil.toString(), "result", "WINDOW_ENDED");
        AuditLog.write(connection, war.attackerId, null, "war.resolve", "war", String.valueOf(war.id), resolution, settings.serverId());
        AuditLog.write(connection, war.defenderId, null, "war.resolve", "war", String.valueOf(war.id), resolution, settings.serverId());
    }

    public WarStatus status(long civilizationId) {
        War war = cache.snapshot().latestCampaign(civilizationId);
        if (war == null) return null;
        long opponentId = war.opponent(civilizationId);
        Civilization opponent = cache.snapshot().civilization(opponentId);
        return new WarStatus(war, opponent);
    }

    public boolean activeOpponents(UUID firstPlayer, UUID secondPlayer, ChunkKey location) {
        StateSnapshot state = cache.snapshot();
        Member first = state.member(firstPlayer);
        Member second = state.member(secondPlayer);
        if (first == null || second == null || first.civilizationId() == second.civilizationId()) return false;
        War war = state.warFor(first.civilizationId());
        if (war == null || war.opponent(first.civilizationId()) == null || war.opponent(first.civilizationId()) != second.civilizationId()) return false;
        Instant now = clock.instant();
        if (war.effectiveState(now) != WarState.ACTIVE) return false;
        Claim claim = state.claim(location);
        if (claim == null || (claim.civilizationId() != first.civilizationId() && claim.civilizationId() != second.civilizationId())) return false;
        return war.rostered(first.civilizationId(), firstPlayer) && war.rostered(second.civilizationId(), secondPlayer);
    }

    public boolean mayBuildInEnemy(UUID playerId, Claim claim, org.bukkit.Material material, boolean placing) {
        StateSnapshot state = cache.snapshot();
        Member member = state.member(playerId);
        if (member == null || member.civilizationId() == claim.civilizationId()) return false;
        War war = state.warFor(member.civilizationId());
        if (war == null || war.opponent(member.civilizationId()) == null || war.opponent(member.civilizationId()) != claim.civilizationId()) return false;
        if (war.effectiveState(clock.instant()) != WarState.ACTIVE || !war.rostered(member.civilizationId(), playerId)) return false;
        if (claim.plotType() == PlotType.CAPITAL && !settings.war().allowCapitalCombat()) return false;
        if (settings.war().alwaysProtectedMaterials().contains(material)) return false;
        return !placing || settings.war().siegePlaceMaterials().contains(material);
    }

    public CompletableFuture<Long> recordTemporaryBlock(UUID actorId, long warId, UUID worldId, int x, int y, int z,
                                                         String originalData, String newData) {
        return database.transaction(connection -> {
            Instant now = clock.instant();
            WarRow currentWar = lockWar(connection, warId);
            if (currentWar == null || currentWar.state != WarState.ACTIVE
                || !WarSchedule.active(now, currentWar.start, currentWar.end)) {
                throw new IllegalStateException("The campaign window is no longer active.");
            }
            Long actorCivilization = rosteredCivilization(connection, warId, actorId);
            if (actorCivilization == null) throw new IllegalStateException("The player is not on the campaign roster.");
            if (actorCivilization != currentWar.attackerId && actorCivilization != currentWar.defenderId) {
                throw new IllegalStateException("The roster entry is not a campaign participant.");
            }
            long defenderCivilization = currentWar.attackerId == actorCivilization
                ? currentWar.defenderId : currentWar.attackerId;
            if (!claimOwnedAt(connection, worldId, Math.floorDiv(x, 16), Math.floorDiv(z, 16), defenderCivilization)) {
                throw new IllegalStateException("The target is no longer an enemy campaign claim.");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO war_block_changes(war_id, actor_uuid, world_uuid, block_x, block_y, block_z,
                    action, original_block_data, new_block_data, temporary, cleaned, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PLACE', ?, ?, TRUE, FALSE, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, warId);
                statement.setBytes(2, UuidBytes.toBytes(actorId));
                statement.setBytes(3, UuidBytes.toBytes(worldId));
                statement.setInt(4, x);
                statement.setInt(5, y);
                statement.setInt(6, z);
                statement.setString(7, originalData);
                statement.setString(8, newData);
                statement.setTimestamp(9, Timestamp.from(now));
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new IllegalStateException("Temporary placement record did not return an id");
                    return keys.getLong(1);
                }
            }
        });
    }

    public CompletableFuture<List<TemporaryBlock>> pendingCleanup(long warId) {
        return database.read(connection -> {
            List<TemporaryBlock> values = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, world_uuid, block_x, block_y, block_z, original_block_data, new_block_data
                FROM war_block_changes
                WHERE war_id = ? AND temporary = TRUE AND cleaned = FALSE
                """)) {
                statement.setLong(1, warId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) values.add(new TemporaryBlock(result.getLong(1), UuidBytes.fromBytes(result.getBytes(2)),
                        result.getInt(3), result.getInt(4), result.getInt(5), result.getString(6), result.getString(7)));
                }
            }
            return List.copyOf(values);
        });
    }

    public CompletableFuture<Set<Long>> campaignsNeedingCleanup() {
        return database.read(connection -> {
            Set<Long> values = new HashSet<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT w.id FROM wars w
                WHERE w.state IN ('CANCELLED', 'TRUCE', 'COMPLETED')
                  AND (EXISTS (SELECT 1 FROM war_block_changes b WHERE b.war_id = w.id
                        AND b.temporary = TRUE AND b.cleaned = FALSE)
                    OR EXISTS (SELECT 1 FROM war_objectives o WHERE o.war_id = w.id
                        AND o.standard_world_uuid IS NOT NULL))
                ORDER BY w.id
                """)) {
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) values.add(result.getLong(1));
                }
            }
            return Set.copyOf(values);
        });
    }

    public CompletableFuture<List<StandardCleanup>> pendingStandardCleanup(long warId) {
        return database.read(connection -> {
            List<StandardCleanup> values = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, standard_world_uuid, standard_x, standard_y, standard_z
                FROM war_objectives WHERE war_id = ? AND standard_world_uuid IS NOT NULL
                """)) {
                statement.setLong(1, warId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) values.add(new StandardCleanup(result.getLong(1),
                        UuidBytes.fromBytes(result.getBytes(2)), result.getInt(3), result.getInt(4), result.getInt(5)));
                }
            }
            return List.copyOf(values);
        });
    }

    public CompletableFuture<Void> markStandardsCleaned(CollectionIds ids) {
        if (ids.ids().isEmpty()) return CompletableFuture.completedFuture(null);
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE war_objectives SET standard_world_uuid = NULL, standard_x = NULL, standard_y = NULL,
                    standard_z = NULL, row_version = row_version + 1 WHERE id = ?
                """)) {
                for (Long id : ids.ids()) {
                    statement.setLong(1, id);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    public CompletableFuture<Void> markCleaned(CollectionIds ids) {
        if (ids.ids().isEmpty()) return CompletableFuture.completedFuture(null);
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE war_block_changes SET cleaned = TRUE WHERE id = ?")) {
                for (Long id : ids.ids()) {
                    statement.setLong(1, id);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    private CompletableFuture<OperationResult> refreshAfterSuccess(OperationResult result) {
        if (!result.success()) return CompletableFuture.completedFuture(result);
        return cache.refreshAfterMutation().handle((ignored, refreshFailure) -> refreshFailure == null ? result
            : OperationResult.ok(result.message() + " Protection remains fail-closed until the cache reload succeeds."));
    }

    private CivWarRow lockCivilization(Connection connection, long civilizationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, current_war_id, peace_shield_until FROM civilizations WHERE id = ? AND status = 'ACTIVE' FOR UPDATE
            """)) {
            statement.setLong(1, civilizationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Civilization is no longer active");
                long war = result.getLong("current_war_id");
                return new CivWarRow(result.getLong("id"), result.wasNull() ? null : war, TimeUtil.instant(result.getTimestamp("peace_shield_until")));
            }
        }
    }

    private WarRow lockWar(Connection connection, long warId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM wars WHERE id = ? FOR UPDATE")) {
            statement.setLong(1, warId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? warRow(result) : null;
            }
        }
    }

    private List<WarRow> currentWarsForUpdate(Connection connection) throws Exception {
        List<WarRow> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT * FROM wars WHERE state IN ('PENDING', 'ACTIVE', 'RESOLVING', 'TRUCE') ORDER BY id FOR UPDATE
            """)) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(warRow(result));
            }
        }
        return values;
    }

    private WarRow warRow(ResultSet result) throws Exception {
        return new WarRow(result.getLong("id"), result.getLong("attacker_civ_id"), result.getLong("defender_civ_id"),
            WarState.valueOf(result.getString("state")), TimeUtil.instant(result.getTimestamp("declared_at")),
            TimeUtil.instant(result.getTimestamp("scheduled_start")), TimeUtil.instant(result.getTimestamp("scheduled_end")),
            TimeUtil.instant(result.getTimestamp("cancellation_grace_until")), TimeUtil.instant(result.getTimestamp("objective_lock_at")),
            TimeUtil.instant(result.getTimestamp("truce_until")), result.getBoolean("attacker_peace"), result.getBoolean("defender_peace"),
            JsonData.costs(result.getString("declaration_cost_snapshot")));
    }

    private Long rosteredCivilization(Connection connection, long warId, UUID actorId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT civ_id FROM war_roster
            WHERE war_id = ? AND player_uuid = ? AND eligible = TRUE FOR UPDATE
            """)) {
            statement.setLong(1, warId);
            statement.setBytes(2, UuidBytes.toBytes(actorId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        }
    }

    private boolean claimOwnedAt(Connection connection, UUID worldId, int chunkX, int chunkZ,
                                 long civilizationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM civ_claims
            WHERE world_uuid = ? AND chunk_x = ? AND chunk_z = ? AND civ_id = ? FOR UPDATE
            """)) {
            statement.setBytes(1, UuidBytes.toBytes(worldId));
            statement.setInt(2, chunkX);
            statement.setInt(3, chunkZ);
            statement.setLong(4, civilizationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private int establishedCount(Connection connection, long civilizationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM civ_members WHERE civ_id = ? AND established = TRUE")) {
            statement.setLong(1, civilizationId);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getInt(1); }
        }
    }

    private boolean shareBorder(Connection connection, long first, long second) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM civ_claims a JOIN civ_claims b ON a.world_uuid = b.world_uuid
                AND ABS(a.chunk_x - b.chunk_x) + ABS(a.chunk_z - b.chunk_z) = 1
            WHERE a.civ_id = ? AND b.civ_id = ? LIMIT 1
            """)) {
            statement.setLong(1, first);
            statement.setLong(2, second);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private boolean inTruce(Connection connection, long first, long second, Instant now) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM wars WHERE ((attacker_civ_id = ? AND defender_civ_id = ?)
                OR (attacker_civ_id = ? AND defender_civ_id = ?)) AND truce_until > ? LIMIT 1
            """)) {
            statement.setLong(1, first); statement.setLong(2, second); statement.setLong(3, second); statement.setLong(4, first);
            statement.setTimestamp(5, Timestamp.from(now));
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private OperationResult verifyStockpile(Connection connection, long civilizationId, Map<ResourceKey, Long> costs) throws Exception {
        for (Map.Entry<ResourceKey, Long> entry : costs.entrySet()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT quantity FROM civ_stockpile WHERE civ_id = ? AND resource_key = ? AND tier = ? FOR UPDATE
                """)) {
                statement.setLong(1, civilizationId);
                statement.setString(2, entry.getKey().family());
                statement.setInt(3, entry.getKey().tier());
                try (ResultSet result = statement.executeQuery()) {
                    long available = result.next() ? result.getLong(1) : 0;
                    if (available < entry.getValue()) return OperationResult.denied("The civic stockpile needs " + entry.getValue()
                        + " " + ResourceText.name(catalogs.resources(), entry.getKey()) + " but has " + available + ".");
                }
            }
        }
        return OperationResult.ok("available");
    }

    private void spendStockpile(Connection connection, long civilizationId, UUID actor, Map<ResourceKey, Long> costs,
                                UUID operationId, String reason, String relatedId) throws Exception {
        for (Map.Entry<ResourceKey, Long> entry : costs.entrySet()) {
            try (PreparedStatement update = connection.prepareStatement("""
                UPDATE civ_stockpile SET quantity = quantity - ? WHERE civ_id = ? AND resource_key = ? AND tier = ? AND quantity >= ?
                """)) {
                update.setLong(1, entry.getValue()); update.setLong(2, civilizationId); update.setString(3, entry.getKey().family());
                update.setInt(4, entry.getKey().tier()); update.setLong(5, entry.getValue());
                if (update.executeUpdate() != 1) throw new IllegalStateException("Stockpile changed during transaction");
            }
            insertStockpileLedger(connection, operationId, civilizationId, actor, entry.getKey(), -entry.getValue(), reason, relatedId);
        }
    }

    private void refundStockpile(Connection connection, long civilizationId, UUID actor, Map<ResourceKey, Long> costs,
                                 UUID operationId, String reason, String relatedId) throws Exception {
        for (Map.Entry<ResourceKey, Long> entry : costs.entrySet()) {
            try (PreparedStatement update = connection.prepareStatement("""
                INSERT INTO civ_stockpile(civ_id, resource_key, tier, quantity) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity)
                """)) {
                update.setLong(1, civilizationId); update.setString(2, entry.getKey().family());
                update.setInt(3, entry.getKey().tier()); update.setLong(4, entry.getValue()); update.executeUpdate();
            }
            insertStockpileLedger(connection, operationId, civilizationId, actor, entry.getKey(), entry.getValue(), reason, relatedId);
        }
    }

    private void insertStockpileLedger(Connection connection, UUID operationId, long civilizationId, UUID actor,
                                       ResourceKey key, long delta, String reason, String relatedId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO stockpile_ledger(operation_id, civ_id, actor_uuid, resource_key, tier, delta, reason,
                related_type, related_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'WAR', ?, ?)
            """)) {
            statement.setBytes(1, UuidBytes.toBytes(operationId)); statement.setLong(2, civilizationId);
            statement.setBytes(3, UuidBytes.toBytes(actor)); statement.setString(4, key.family()); statement.setInt(5, key.tier());
            statement.setLong(6, delta); statement.setString(7, reason); statement.setString(8, relatedId);
            statement.setTimestamp(9, Timestamp.from(clock.instant())); statement.executeUpdate();
        }
    }

    private void cancelWar(Connection connection, WarRow war, UUID actorId, String reason) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE wars SET state = 'CANCELLED', result = ?, resolved_at = ?, row_version = row_version + 1 WHERE id = ?
            """)) {
            statement.setString(1, reason); statement.setTimestamp(2, Timestamp.from(clock.instant())); statement.setLong(3, war.id);
            statement.executeUpdate();
        }
        clearWarLocks(connection, war);
        AuditLog.write(connection, war.attackerId, actorId, "war.cancel", "war", String.valueOf(war.id), Map.of("reason", reason), settings.serverId());
        AuditLog.write(connection, war.defenderId, actorId, "war.cancel", "war", String.valueOf(war.id), Map.of("reason", reason), settings.serverId());
    }

    private void clearWarLocks(Connection connection, WarRow war) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE civilizations SET current_war_id = NULL, row_version = row_version + 1 WHERE id IN (?, ?) AND current_war_id = ?")) {
            statement.setLong(1, war.attackerId); statement.setLong(2, war.defenderId); statement.setLong(3, war.id); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE civ_members SET membership_locked = FALSE WHERE civ_id IN (?, ?)")) {
            statement.setLong(1, war.attackerId); statement.setLong(2, war.defenderId); statement.executeUpdate();
        }
    }

    private void updateWarState(Connection connection, long warId, WarState state) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE wars SET state = ?, row_version = row_version + 1 WHERE id = ?")) {
            statement.setString(1, state.name()); statement.setLong(2, warId); statement.executeUpdate();
        }
    }

    public record TransitionResult(Set<Long> activated, Set<Long> resolved, Set<Long> truceExpired) {
        static TransitionResult empty() { return new TransitionResult(Set.of(), Set.of(), Set.of()); }
        boolean changed() { return !activated.isEmpty() || !resolved.isEmpty() || !truceExpired.isEmpty(); }
    }
    public record WarStatus(War war, Civilization opponent) {}
    public record TemporaryBlock(long id, UUID worldId, int x, int y, int z, String originalBlockData,
                                 String placedBlockData) {}
    public record StandardCleanup(long objectiveId, UUID worldId, int x, int y, int z) {}
    public record CollectionIds(List<Long> ids) {}

    private record CivWarRow(long id, Long currentWarId, Instant peaceShieldUntil) {}
    private record WarRow(long id, long attackerId, long defenderId, WarState state, Instant declaredAt,
                          Instant start, Instant end, Instant graceUntil, Instant objectiveLockAt, Instant truceUntil,
                          boolean attackerPeace, boolean defenderPeace, Map<ResourceKey, Long> declarationCost) {}
}
