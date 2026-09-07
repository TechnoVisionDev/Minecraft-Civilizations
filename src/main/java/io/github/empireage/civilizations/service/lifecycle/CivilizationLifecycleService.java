package io.github.empireage.civilizations.service.lifecycle;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.concurrent.CivilizationLocks;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.config.TechnologyCatalog;
import io.github.empireage.civilizations.database.AuditLog;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.database.JsonData;
import io.github.empireage.civilizations.database.Sql;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.Civilization;
import io.github.empireage.civilizations.domain.CivilizationStatus;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.domain.Role;
import io.github.empireage.civilizations.domain.TechnologyDefinition;
import io.github.empireage.civilizations.service.lifecycle.LifecycleModels.ActivityResult;
import io.github.empireage.civilizations.service.lifecycle.LifecycleModels.ActivityUpdate;
import io.github.empireage.civilizations.service.lifecycle.LifecycleModels.ChatPreference;
import io.github.empireage.civilizations.service.lifecycle.LifecycleModels.CivilizationInfo;
import io.github.empireage.civilizations.service.lifecycle.LifecycleModels.CivilizationListEntry;
import io.github.empireage.civilizations.service.lifecycle.LifecycleModels.CreateRequest;
import io.github.empireage.civilizations.service.lifecycle.LifecycleModels.CreateResult;
import io.github.empireage.civilizations.service.lifecycle.LifecycleModels.DisbandResult;
import io.github.empireage.civilizations.service.lifecycle.LifecycleModels.InvitationInfo;
import io.github.empireage.civilizations.service.lifecycle.LifecycleModels.MemberInfo;
import io.github.empireage.civilizations.service.lifecycle.LifecycleModels.MembershipCooldown;
import io.github.empireage.civilizations.util.NameNormalizer;
import io.github.empireage.civilizations.util.TimeUtil;
import io.github.empireage.civilizations.util.UuidBytes;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Complete civilization founding, membership, role, archival, activity, and
 * civilization-chat lifecycle. All database work is asynchronous and every
 * committed mutation is followed by a cache refresh.
 */
public final class CivilizationLifecycleService {
    private static final long FOUNDING_LOCK_KEY = Long.MIN_VALUE;

    private final Database database;
    private final StateCache stateCache;
    private final CivilizationLocks locks;
    private final Settings settings;
    private final TechnologyCatalog technologies;
    private final Clock clock;
    // Main-thread login/logout observations close the gap before asynchronous activity saves.
    private final Map<UUID, Presence> presence = new ConcurrentHashMap<>();

    public CivilizationLifecycleService(Database database, StateCache stateCache, CivilizationLocks locks,
                                         Settings settings, TechnologyCatalog technologies) {
        this(database, stateCache, locks, settings, technologies, Clock.systemUTC());
    }

    public CivilizationLifecycleService(Database database, StateCache stateCache, CivilizationLocks locks,
                                         Settings settings, TechnologyCatalog technologies, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.stateCache = Objects.requireNonNull(stateCache, "stateCache");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.technologies = Objects.requireNonNull(technologies, "technologies");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CompletableFuture<CreateResult> create(CreateRequest request) {
        Objects.requireNonNull(request, "request");
        OperationResult ready = mutationReady();
        if (!ready.success()) return CompletableFuture.completedFuture(CreateResult.denied(ready.message(), request.inventory()));

        LifecyclePolicy.IdentityValidation identity = LifecyclePolicy.validateIdentity(request.civilizationName());
        if (!identity.valid()) return CompletableFuture.completedFuture(CreateResult.denied(identity.message(), request.inventory()));
        OperationResult requestValidation = validateCreateRequest(request);
        if (!requestValidation.success()) {
            return CompletableFuture.completedFuture(CreateResult.denied(requestValidation.message(), request.inventory()));
        }

        StateSnapshot snapshot = stateCache.snapshot();
        if (snapshot.member(request.founderId()) != null) {
            return CompletableFuture.completedFuture(CreateResult.denied("You already belong to a civilization.", request.inventory()));
        }
        if (snapshot.civilization(identity.identity().name()) != null) {
            return CompletableFuture.completedFuture(CreateResult.denied("That civilization name is already in use.", request.inventory()));
        }
        if (snapshot.claim(request.capitalChunk()) != null) {
            return CompletableFuture.completedFuture(CreateResult.denied("The founding chunk is already claimed.", request.inventory()));
        }
        int minimumDistance = settings.founding().minimumDistanceChunks();
        if (minimumDistance > 0 && snapshot.claims().keySet().stream()
            .anyMatch(key -> key.chebyshevDistance(request.capitalChunk()) < minimumDistance)) {
            return CompletableFuture.completedFuture(CreateResult.denied(
                "The capital must be at least " + minimumDistance + " chunks from every existing claim.", request.inventory()));
        }

        return executeLocked(FOUNDING_LOCK_KEY, playerLockKey(request.founderId()), connection -> createTransaction(connection, request),
            error -> CreateResult.denied(failureMessage("create the civilization", error), request.inventory()));
    }

    private TxOutcome<CreateResult> createTransaction(Connection connection, CreateRequest request) throws Exception {
        lockFoundingCoordinator(connection);
        Instant now = clock.instant();
        LifecyclePolicy.IdentityValidation identity = LifecyclePolicy.validateIdentity(request.civilizationName());
        if (!identity.valid()) return unchanged(CreateResult.denied(identity.message(), request.inventory()));
        OperationResult validation = validateCreateRequest(request);
        if (!validation.success()) return unchanged(CreateResult.denied(validation.message(), request.inventory()));

        OperationResult chargeValidation = lockAndValidateFoundingCharge(connection, request);
        if (!chargeValidation.success()) return unchanged(CreateResult.denied(chargeValidation.message(), request.inventory()));

        if (lockMembershipAnywhere(connection, request.founderId()) != null) {
            return unchanged(CreateResult.denied("You already belong to a civilization.", request.inventory()));
        }
        Instant cooldown = latestCooldown(connection, request.founderId(), true);
        if (cooldown != null && cooldown.isAfter(now)) {
            return unchanged(CreateResult.denied("You may found another civilization after " + cooldown + ".", request.inventory()));
        }

        LifecyclePolicy.ValidatedIdentity value = identity.identity();
        if (identityTaken(connection, value.normalizedName())) {
            return unchanged(CreateResult.denied("That civilization name is already in use.", request.inventory()));
        }
        if (foundingAreaOccupied(connection, request.capitalChunk(), settings.founding().minimumDistanceChunks())) {
            return unchanged(CreateResult.denied("The founding chunk is claimed or too close to another civilization.", request.inventory()));
        }

        FoundingInventory.Withdrawal withdrawal;
        try {
            withdrawal = request.inventory().withdraw(settings.founding().materialCost());
        } catch (FoundingInventory.InsufficientFoundingMaterialsException exception) {
            return unchanged(CreateResult.denied(exception.getMessage(), request.inventory()));
        }

        long civilizationId;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO civilizations(
                name, normalized_name, leader_uuid, status,
                capital_world_uuid, capital_world_name, capital_chunk_x, capital_chunk_z,
                home_x, home_y, home_z, home_yaw, home_pitch, default_plot_price,
                treasury_balance, knowledge_balance, peace_shield_until, admin_claim_bonus,
                created_at, row_version
            ) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0.00, 0, ?, 0, ?, 0)
            """, Statement.RETURN_GENERATED_KEYS)) {
            Sql.bind(statement, value.name(), value.normalizedName(), request.founderId(),
                request.capitalChunk().worldId(), request.worldName(), request.capitalChunk().x(), request.capitalChunk().z(),
                request.home().x(), request.home().y(), request.home().z(), request.home().yaw(), request.home().pitch(),
                settings.plots().defaultPrice(), now.plus(settings.founding().peaceShield()), now);
            statement.executeUpdate();
            civilizationId = Sql.generatedId(statement);
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO civ_members(civ_id, player_uuid, last_known_name, role, joined_at, last_active_at,
                eligible_playtime_seconds, established, contribution_total, membership_locked)
            VALUES (?, ?, ?, 'LEADER', ?, ?, 0, FALSE, 0, FALSE)
            """)) {
            Sql.bind(statement, civilizationId, request.founderId(), request.founderName(), now, now);
            statement.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO civ_claims(world_uuid, world_name, chunk_x, chunk_z, civ_id, plot_type,
                acquisition_source, claim_cost_snapshot, claimed_at, claimed_by, row_version)
            VALUES (?, ?, ?, ?, ?, 'CAPITAL', 'FOUNDING', ?, ?, ?, 0)
            """)) {
            Sql.bind(statement, request.capitalChunk().worldId(), request.worldName(), request.capitalChunk().x(),
                request.capitalChunk().z(), civilizationId, JsonData.costs(withdrawal.removed()), now, request.founderId());
            statement.executeUpdate();
        }
        if (request.foundingOperationId() != null) {
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE economy_operations SET civ_id = ?, updated_at = ?
                WHERE operation_id = ? AND operation_type = 'CIVILIZATION_FOUNDING'
                  AND state = 'EXTERNAL_APPLIED' AND civ_id IS NULL
                """)) {
                Sql.bind(statement, civilizationId, now, request.foundingOperationId());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Founding payment changed while committing the civilization");
                }
            }
        }
        upsertPlayerSettings(connection, request.founderId(), request.founderName(), false, now, false);
        AuditLog.write(connection, civilizationId, request.founderId(), "civilization.created", "civilization",
            Long.toString(civilizationId), Map.of(
                "name", value.name(), "capital", request.capitalChunk().compact(),
                "materialCost", JsonData.costs(withdrawal.removed()), "moneyCost", settings.founding().moneyCost().toPlainString()
            ), settings.serverId());

        return changed(new CreateResult(OperationResult.ok("Civilization " + value.name() + " was founded."),
            civilizationId, withdrawal.removed(), withdrawal.remaining(), settings.founding().moneyCost()));
    }

    /** The row lock remains held through Database.transaction's commit. */
    private void lockFoundingCoordinator(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT lock_key FROM civ_coordination_locks WHERE lock_key = 'founding' FOR UPDATE");
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new IllegalStateException("The founding coordination row is missing");
        }
    }

    public CompletableFuture<Optional<CivilizationInfo>> info(String name) {
        if (name == null || name.isBlank()) return CompletableFuture.completedFuture(Optional.empty());
        Civilization civilization = stateCache.snapshot().civilization(name);
        return CompletableFuture.completedFuture(Optional.ofNullable(civilization).map(this::toInfo));
    }

    public CompletableFuture<Optional<CivilizationInfo>> info(long civilizationId) {
        return CompletableFuture.completedFuture(Optional.ofNullable(stateCache.snapshot().civilization(civilizationId)).map(this::toInfo));
    }

    public CompletableFuture<List<CivilizationListEntry>> list() {
        StateSnapshot snapshot = stateCache.snapshot();
        List<CivilizationListEntry> entries = snapshot.civilizations().values().stream()
            .filter(civilization -> civilization.status() == CivilizationStatus.ACTIVE)
            .map(civilization -> new CivilizationListEntry(civilization.id(), civilization.name(),
                snapshot.members(civilization.id()).size(), snapshot.claims(civilization.id()).size(), civilization.createdAt(),
                civilization.warLocked()))
            .sorted(Comparator.comparing(CivilizationListEntry::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
        return CompletableFuture.completedFuture(entries);
    }

    public CompletableFuture<OperationResult> invite(UUID actorId, UUID targetId, String targetName) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(targetId, "targetId");
        OperationResult ready = mutationReady();
        if (!ready.success()) return CompletableFuture.completedFuture(ready);
        OperationResult nameValidation = LifecyclePolicy.validatePlayerName(targetName);
        if (!nameValidation.success()) return CompletableFuture.completedFuture(nameValidation);
        if (actorId.equals(targetId)) return CompletableFuture.completedFuture(OperationResult.denied("You cannot invite yourself."));

        StateSnapshot snapshot = stateCache.snapshot();
        Member actor = snapshot.member(actorId);
        if (actor == null) return CompletableFuture.completedFuture(OperationResult.denied("You do not belong to a civilization."));
        if (!actor.role().atLeast(Role.ADVISOR)) return CompletableFuture.completedFuture(OperationResult.denied("Only leaders and advisors may invite players."));
        if (actor.membershipLocked() || snapshot.warFor(actor.civilizationId()) != null) {
            return CompletableFuture.completedFuture(OperationResult.denied("Membership is frozen during a campaign."));
        }
        if (snapshot.member(targetId) != null) return CompletableFuture.completedFuture(OperationResult.denied("That player already belongs to a civilization."));

        return executeLocked(actor.civilizationId(), playerLockKey(targetId), connection -> inviteTransaction(connection, actor.civilizationId(), actorId,
            targetId, targetName), error -> OperationResult.denied(failureMessage("send the invitation", error)));
    }

    private TxOutcome<OperationResult> inviteTransaction(Connection connection, long civilizationId, UUID actorId,
                                                         UUID targetId, String targetName) throws Exception {
        Instant now = clock.instant();
        LockedCivilization civilization = lockCivilization(connection, civilizationId);
        if (!active(civilization)) return unchanged(OperationResult.denied("The civilization is not active."));
        LockedMember actor = lockMember(connection, civilizationId, actorId);
        if (actor == null || !actor.role().atLeast(Role.ADVISOR)) {
            return unchanged(OperationResult.denied("Only leaders and advisors may invite players."));
        }
        if (actor.membershipLocked() || civilization.currentWarId() != null) {
            return unchanged(OperationResult.denied("Membership is frozen during a campaign."));
        }
        if (lockMembershipAnywhere(connection, targetId) != null) {
            return unchanged(OperationResult.denied("That player already belongs to a civilization."));
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO civ_invites(civ_id, target_uuid, target_name, inviter_uuid, created_at, expires_at, status)
            VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
            ON DUPLICATE KEY UPDATE target_name = VALUES(target_name), inviter_uuid = VALUES(inviter_uuid),
                created_at = VALUES(created_at), expires_at = VALUES(expires_at)
            """)) {
            Sql.bind(statement, civilizationId, targetId, targetName, actorId, now, now.plus(settings.membership().inviteExpiry()));
            statement.executeUpdate();
        }
        AuditLog.write(connection, civilizationId, actorId, "membership.invited", "player", targetId.toString(),
            Map.of("targetName", targetName, "expiresAt", now.plus(settings.membership().inviteExpiry()).toString()), settings.serverId());
        return changed(OperationResult.ok(targetName + " was invited to " + civilization.name() + "."));
    }

    public CompletableFuture<List<InvitationInfo>> invitations(UUID targetId) {
        Objects.requireNonNull(targetId, "targetId");
        if (!database.healthy()) return CompletableFuture.completedFuture(List.of());
        Instant now = clock.instant();
        return database.read(connection -> {
            List<InvitationInfo> values = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT i.id, i.civ_id, c.name, i.target_uuid, i.target_name, i.inviter_uuid,
                    i.created_at, i.expires_at
                FROM civ_invites i JOIN civilizations c ON c.id = i.civ_id
                WHERE i.target_uuid = ? AND i.status = 'ACTIVE' AND i.expires_at > ? AND c.status = 'ACTIVE'
                ORDER BY i.expires_at, c.name
                """)) {
                Sql.bind(statement, targetId, now);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) values.add(new InvitationInfo(result.getLong(1), result.getLong(2),
                        result.getString(3), UuidBytes.fromBytes(result.getBytes(4)), result.getString(5),
                        UuidBytes.fromBytes(result.getBytes(6)), TimeUtil.instant(result.getTimestamp(7)),
                        TimeUtil.instant(result.getTimestamp(8))));
                }
            }
            return List.copyOf(values);
        }).exceptionally(ignored -> List.of());
    }

    public CompletableFuture<OperationResult> accept(UUID targetId, String civilizationName) {
        Objects.requireNonNull(targetId, "targetId");
        OperationResult ready = mutationReady();
        if (!ready.success()) return CompletableFuture.completedFuture(ready);
        if (civilizationName == null || civilizationName.isBlank()) {
            return CompletableFuture.completedFuture(OperationResult.denied("Choose a civilization invitation to accept."));
        }
        StateSnapshot snapshot = stateCache.snapshot();
        if (snapshot.member(targetId) != null) return CompletableFuture.completedFuture(OperationResult.denied("You already belong to a civilization."));
        Civilization civilization = snapshot.civilization(civilizationName);
        if (civilization == null) return CompletableFuture.completedFuture(OperationResult.denied("That civilization does not exist."));
        if (civilization.warLocked()) return CompletableFuture.completedFuture(OperationResult.denied("Membership is frozen during a campaign."));

        return executeLocked(civilization.id(), playerLockKey(targetId),
            connection -> acceptTransaction(connection, civilization.id(), targetId),
            error -> OperationResult.denied(failureMessage("accept the invitation", error)));
    }

    private TxOutcome<OperationResult> acceptTransaction(Connection connection, long civilizationId, UUID targetId) throws Exception {
        Instant now = clock.instant();
        LockedCivilization civilization = lockCivilization(connection, civilizationId);
        if (!active(civilization)) return unchanged(OperationResult.denied("That civilization is not active."));
        if (civilization.currentWarId() != null) return unchanged(OperationResult.denied("Membership is frozen during a campaign."));
        if (lockMembershipAnywhere(connection, targetId) != null) {
            return unchanged(OperationResult.denied("You already belong to a civilization."));
        }
        Instant cooldown = latestCooldown(connection, targetId, true);
        if (cooldown != null && cooldown.isAfter(now)) {
            return unchanged(OperationResult.denied("You may join another civilization after " + cooldown + "."));
        }

        LockedInvitation invitation = lockInvitation(connection, civilizationId, targetId);
        if (invitation == null || !invitation.expiresAt().isAfter(now)) {
            return unchanged(OperationResult.denied("That invitation has expired or no longer exists."));
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO civ_members(civ_id, player_uuid, last_known_name, role, joined_at, last_active_at,
                eligible_playtime_seconds, established, contribution_total, membership_locked)
            VALUES (?, ?, ?, 'CITIZEN', ?, ?, 0, FALSE, 0, FALSE)
            """)) {
            Sql.bind(statement, civilizationId, targetId, invitation.targetName(), now, now);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM civ_invites WHERE id = ?")) {
            statement.setLong(1, invitation.id());
            statement.executeUpdate();
        }
        upsertPlayerSettings(connection, targetId, invitation.targetName(), false, now, true);
        AuditLog.write(connection, civilizationId, targetId, "membership.joined", "player", targetId.toString(),
            Map.of("inviteId", invitation.id(), "inviter", invitation.inviterId().toString()), settings.serverId());
        return changed(OperationResult.ok("You joined " + civilization.name() + "."));
    }

    public CompletableFuture<OperationResult> deny(UUID targetId, String civilizationName) {
        Objects.requireNonNull(targetId, "targetId");
        OperationResult ready = mutationReady();
        if (!ready.success()) return CompletableFuture.completedFuture(ready);
        Civilization civilization = civilizationName == null ? null : stateCache.snapshot().civilization(civilizationName);
        if (civilization == null) return CompletableFuture.completedFuture(OperationResult.denied("That civilization does not exist."));
        return executeLocked(civilization.id(), connection -> denyTransaction(connection, civilization.id(), targetId),
            error -> OperationResult.denied(failureMessage("deny the invitation", error)));
    }

    private TxOutcome<OperationResult> denyTransaction(Connection connection, long civilizationId, UUID targetId) throws Exception {
        LockedCivilization civilization = lockCivilization(connection, civilizationId);
        if (civilization == null) return unchanged(OperationResult.denied("That civilization does not exist."));
        LockedInvitation invitation = lockInvitation(connection, civilizationId, targetId);
        if (invitation == null) return unchanged(OperationResult.denied("No active invitation from that civilization exists."));
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM civ_invites WHERE id = ?")) {
            statement.setLong(1, invitation.id());
            statement.executeUpdate();
        }
        AuditLog.write(connection, civilizationId, targetId, "membership.invitation_denied", "invitation",
            Long.toString(invitation.id()), Map.of(), settings.serverId());
        return changed(OperationResult.ok("Invitation from " + civilization.name() + " denied."));
    }

    public CompletableFuture<MembershipCooldown> cooldown(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!database.healthy()) return CompletableFuture.completedFuture(MembershipCooldown.none());
        Instant now = clock.instant();
        return database.read(connection -> {
            Instant until = latestCooldown(connection, playerId, false);
            return until != null && until.isAfter(now)
                ? new MembershipCooldown(true, until, Duration.between(now, until))
                : MembershipCooldown.none();
        }).exceptionally(ignored -> MembershipCooldown.none());
    }

    public CompletableFuture<OperationResult> leave(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        OperationResult ready = mutationReady();
        if (!ready.success()) return CompletableFuture.completedFuture(ready);
        StateSnapshot snapshot = stateCache.snapshot();
        Member member = snapshot.member(playerId);
        if (member == null) return CompletableFuture.completedFuture(OperationResult.denied("You do not belong to a civilization."));
        if (member.role() == Role.LEADER) {
            return CompletableFuture.completedFuture(OperationResult.denied("Transfer leadership or disband before leaving."));
        }
        if (member.membershipLocked() || snapshot.warFor(member.civilizationId()) != null) {
            return CompletableFuture.completedFuture(OperationResult.denied("Membership is frozen during a campaign."));
        }
        return executeLocked(member.civilizationId(), connection -> leaveTransaction(connection, member.civilizationId(), playerId),
            error -> OperationResult.denied(failureMessage("leave the civilization", error)));
    }

    private TxOutcome<OperationResult> leaveTransaction(Connection connection, long civilizationId, UUID playerId) throws Exception {
        Instant now = clock.instant();
        LockedCivilization civilization = lockCivilization(connection, civilizationId);
        if (!active(civilization)) return unchanged(OperationResult.denied("The civilization is not active."));
        LockedMember member = lockMember(connection, civilizationId, playerId);
        if (member == null) return unchanged(OperationResult.denied("You do not belong to that civilization."));
        if (member.role() == Role.LEADER) return unchanged(OperationResult.denied("Transfer leadership or disband before leaving."));
        if (member.membershipLocked() || civilization.currentWarId() != null) {
            return unchanged(OperationResult.denied("Membership is frozen during a campaign."));
        }
        int terminatedPlots = terminateMemberProperty(connection, civilizationId, playerId);
        archiveMembership(connection, member, now, "LEAVE");
        deleteMembership(connection, civilizationId, playerId);
        disableChatPreference(connection, playerId, now);
        AuditLog.write(connection, civilizationId, playerId, "membership.left", "player", playerId.toString(),
            Map.of("role", member.role().name(), "terminatedPrivatePlots", terminatedPlots,
                "cooldownUntil", LifecyclePolicy.cooldownUntil(now, settings.membership().changeCooldown()).toString()), settings.serverId());
        return changed(OperationResult.ok("You left " + civilization.name() + ". Private plot rights were terminated without refund."));
    }

    public CompletableFuture<OperationResult> kick(UUID actorId, UUID targetId, Set<Long> confirmedPrivatePlotIds) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(targetId, "targetId");
        OperationResult ready = mutationReady();
        if (!ready.success()) return CompletableFuture.completedFuture(ready);
        if (actorId.equals(targetId)) return CompletableFuture.completedFuture(OperationResult.denied("Use /civ leave to leave yourself."));

        StateSnapshot snapshot = stateCache.snapshot();
        Member actor = snapshot.member(actorId);
        Member target = snapshot.member(targetId);
        if (actor == null) return CompletableFuture.completedFuture(OperationResult.denied("You do not belong to a civilization."));
        if (!actor.role().atLeast(Role.ADVISOR)) return CompletableFuture.completedFuture(OperationResult.denied("Only leaders and advisors may kick citizens."));
        if (target == null || target.civilizationId() != actor.civilizationId()) {
            return CompletableFuture.completedFuture(OperationResult.denied("That player is not a member of your civilization."));
        }
        if (target.role() != Role.CITIZEN) {
            return CompletableFuture.completedFuture(OperationResult.denied("Only ordinary citizens can be kicked."));
        }
        if (actor.membershipLocked() || target.membershipLocked() || snapshot.warFor(actor.civilizationId()) != null) {
            return CompletableFuture.completedFuture(OperationResult.denied("Membership is frozen during a campaign."));
        }
        Set<Long> cachedPlots = snapshot.claims(actor.civilizationId()).stream()
            .filter(claim -> targetId.equals(claim.plotOwnerId())).map(io.github.empireage.civilizations.domain.Claim::id)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!cachedPlots.equals(confirmedPrivatePlotIds)) {
            return CompletableFuture.completedFuture(OperationResult.denied("Confirm the kick again: it will terminate " + cachedPlots.size()
                + " private plot right(s) without refund."));
        }

        return executeLocked(actor.civilizationId(), connection -> kickTransaction(connection, actor.civilizationId(), actorId,
            targetId, confirmedPrivatePlotIds), error -> OperationResult.denied(failureMessage("kick that citizen", error)));
    }

    private TxOutcome<OperationResult> kickTransaction(Connection connection, long civilizationId, UUID actorId,
                                                       UUID targetId, Set<Long> confirmedPrivatePlotIds) throws Exception {
        Instant now = clock.instant();
        LockedCivilization civilization = lockCivilization(connection, civilizationId);
        if (!active(civilization)) return unchanged(OperationResult.denied("The civilization is not active."));
        LockedMember actor = lockMember(connection, civilizationId, actorId);
        LockedMember target = lockMember(connection, civilizationId, targetId);
        if (actor == null || !actor.role().atLeast(Role.ADVISOR)) {
            return unchanged(OperationResult.denied("Only leaders and advisors may kick citizens."));
        }
        if (target == null || target.role() != Role.CITIZEN) {
            return unchanged(OperationResult.denied("Only ordinary citizens can be kicked."));
        }
        if (actor.membershipLocked() || target.membershipLocked() || civilization.currentWarId() != null) {
            return unchanged(OperationResult.denied("Membership is frozen during a campaign."));
        }
        Set<Long> privatePlots = lockPrivatePlotIds(connection, civilizationId, targetId);
        if (!privatePlots.equals(confirmedPrivatePlotIds)) {
            return unchanged(OperationResult.denied("Private plot rights changed after confirmation; preview the kick again."));
        }
        int terminatedPlots = terminateMemberProperty(connection, civilizationId, targetId);
        archiveMembership(connection, target, now, "KICK");
        deleteMembership(connection, civilizationId, targetId);
        disableChatPreference(connection, targetId, now);
        AuditLog.write(connection, civilizationId, actorId, "membership.kicked", "player", targetId.toString(),
            Map.of("targetName", target.lastKnownName(), "terminatedPrivatePlots", terminatedPlots,
                "confirmedPrivatePlotIds", confirmedPrivatePlotIds,
                "cooldownUntil", LifecyclePolicy.cooldownUntil(now, settings.membership().changeCooldown()).toString()), settings.serverId());
        return changed(OperationResult.ok(target.lastKnownName() + " was removed. Private plot rights ended without refund."));
    }

    public CompletableFuture<OperationResult> promoteAdvisor(UUID leaderId, UUID targetId) {
        return changeAdvisorRole(leaderId, targetId, true);
    }

    public CompletableFuture<OperationResult> demoteAdvisor(UUID leaderId, UUID targetId) {
        return changeAdvisorRole(leaderId, targetId, false);
    }

    private CompletableFuture<OperationResult> changeAdvisorRole(UUID leaderId, UUID targetId, boolean promote) {
        Objects.requireNonNull(leaderId, "leaderId");
        Objects.requireNonNull(targetId, "targetId");
        OperationResult ready = mutationReady();
        if (!ready.success()) return CompletableFuture.completedFuture(ready);
        StateSnapshot snapshot = stateCache.snapshot();
        Member leader = snapshot.member(leaderId);
        Member target = snapshot.member(targetId);
        if (leader == null || leader.role() != Role.LEADER) {
            return CompletableFuture.completedFuture(OperationResult.denied("Only the civilization leader may manage advisors."));
        }
        if (target == null || target.civilizationId() != leader.civilizationId()) {
            return CompletableFuture.completedFuture(OperationResult.denied("That player is not a member of your civilization."));
        }
        Role required = promote ? Role.CITIZEN : Role.ADVISOR;
        if (target.role() != required) {
            return CompletableFuture.completedFuture(OperationResult.denied(promote
                ? "Only citizens can be promoted to advisor." : "That member is not an advisor."));
        }
        if (leader.membershipLocked() || target.membershipLocked() || snapshot.warFor(leader.civilizationId()) != null) {
            return CompletableFuture.completedFuture(OperationResult.denied("Role changes are frozen during a campaign."));
        }
        if (promote) {
            int current = (int) snapshot.members(leader.civilizationId()).stream().filter(member -> member.role() == Role.ADVISOR).count();
            OperationResult cap = LifecyclePolicy.validateAdvisorPromotion(current, advisorLimit(snapshot, leader.civilizationId()));
            if (!cap.success()) return CompletableFuture.completedFuture(cap);
        }
        return executeLocked(leader.civilizationId(), connection -> advisorRoleTransaction(connection, leader.civilizationId(),
            leaderId, targetId, promote), error -> OperationResult.denied(failureMessage("change that role", error)));
    }

    private TxOutcome<OperationResult> advisorRoleTransaction(Connection connection, long civilizationId, UUID leaderId,
                                                              UUID targetId, boolean promote) throws Exception {
        LockedCivilization civilization = lockCivilization(connection, civilizationId);
        if (!active(civilization)) return unchanged(OperationResult.denied("The civilization is not active."));
        LockedMember leader = lockMember(connection, civilizationId, leaderId);
        LockedMember target = lockMember(connection, civilizationId, targetId);
        if (leader == null || leader.role() != Role.LEADER || !leaderId.equals(civilization.leaderId())) {
            return unchanged(OperationResult.denied("Only the civilization leader may manage advisors."));
        }
        Role required = promote ? Role.CITIZEN : Role.ADVISOR;
        if (target == null || target.role() != required) {
            return unchanged(OperationResult.denied(promote ? "Only citizens can be promoted to advisor." : "That member is not an advisor."));
        }
        if (leader.membershipLocked() || target.membershipLocked() || civilization.currentWarId() != null) {
            return unchanged(OperationResult.denied("Role changes are frozen during a campaign."));
        }
        if (promote) {
            int current = lockAdvisorCount(connection, civilizationId);
            OperationResult cap = LifecyclePolicy.validateAdvisorPromotion(current, advisorLimit(connection, civilizationId));
            if (!cap.success()) return unchanged(cap);
        }
        Role newRole = promote ? Role.ADVISOR : Role.CITIZEN;
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE civ_members SET role = ? WHERE civ_id = ? AND player_uuid = ?")) {
            Sql.bind(statement, newRole, civilizationId, targetId);
            statement.executeUpdate();
        }
        AuditLog.write(connection, civilizationId, leaderId, promote ? "membership.advisor_promoted" : "membership.advisor_demoted",
            "player", targetId.toString(), Map.of("targetName", target.lastKnownName(), "newRole", newRole.name()), settings.serverId());
        return changed(OperationResult.ok(target.lastKnownName() + " is now " + newRole.name().toLowerCase(Locale.ROOT) + "."));
    }

    public CompletableFuture<OperationResult> transferLeadership(UUID leaderId, UUID recipientId) {
        Objects.requireNonNull(leaderId, "leaderId");
        Objects.requireNonNull(recipientId, "recipientId");
        OperationResult ready = mutationReady();
        if (!ready.success()) return CompletableFuture.completedFuture(ready);
        if (leaderId.equals(recipientId)) return CompletableFuture.completedFuture(OperationResult.denied("You are already the leader."));
        StateSnapshot snapshot = stateCache.snapshot();
        Member leader = snapshot.member(leaderId);
        Member recipient = snapshot.member(recipientId);
        if (leader == null || leader.role() != Role.LEADER) {
            return CompletableFuture.completedFuture(OperationResult.denied("Only the civilization leader may transfer leadership."));
        }
        if (recipient == null || recipient.civilizationId() != leader.civilizationId()) {
            return CompletableFuture.completedFuture(OperationResult.denied("The recipient must already belong to your civilization."));
        }
        if (leader.membershipLocked() || recipient.membershipLocked() || snapshot.warFor(leader.civilizationId()) != null) {
            return CompletableFuture.completedFuture(OperationResult.denied("Leadership transfer is frozen during a campaign."));
        }
        return executeLocked(leader.civilizationId(), connection -> transferTransaction(connection, leader.civilizationId(),
            leaderId, recipientId), error -> OperationResult.denied(failureMessage("transfer leadership", error)));
    }

    private TxOutcome<OperationResult> transferTransaction(Connection connection, long civilizationId, UUID leaderId,
                                                           UUID recipientId) throws Exception {
        LockedCivilization civilization = lockCivilization(connection, civilizationId);
        if (!active(civilization)) return unchanged(OperationResult.denied("The civilization is not active."));
        LockedMember leader = lockMember(connection, civilizationId, leaderId);
        LockedMember recipient = lockMember(connection, civilizationId, recipientId);
        if (leader == null || leader.role() != Role.LEADER || !leaderId.equals(civilization.leaderId())) {
            return unchanged(OperationResult.denied("Only the civilization leader may transfer leadership."));
        }
        if (recipient == null || recipient.role() == Role.LEADER) {
            return unchanged(OperationResult.denied("The recipient must already be a non-leader member."));
        }
        if (leader.membershipLocked() || recipient.membershipLocked() || civilization.currentWarId() != null) {
            return unchanged(OperationResult.denied("Leadership transfer is frozen during a campaign."));
        }
        int advisors = lockAdvisorCount(connection, civilizationId);
        int limit = advisorLimit(connection, civilizationId);
        boolean recipientWasAdvisor = recipient.role() == Role.ADVISOR;
        Role formerLeaderRole = recipientWasAdvisor || advisors < limit ? Role.ADVISOR : Role.CITIZEN;

        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE civilizations SET leader_uuid = ?, row_version = row_version + 1 WHERE id = ?")) {
            Sql.bind(statement, recipientId, civilizationId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE civ_members SET role = CASE WHEN player_uuid = ? THEN 'LEADER' ELSE ? END "
                + "WHERE civ_id = ? AND player_uuid IN (?, ?)")) {
            Sql.bind(statement, recipientId, formerLeaderRole, civilizationId, leaderId, recipientId);
            statement.executeUpdate();
        }
        AuditLog.write(connection, civilizationId, leaderId, "civilization.leadership_transferred", "player",
            recipientId.toString(), Map.of("newLeaderName", recipient.lastKnownName(), "formerLeaderRole", formerLeaderRole.name()),
            settings.serverId());
        return changed(OperationResult.ok("Leadership transferred to " + recipient.lastKnownName() + "."));
    }

    public void recordPresence(UUID playerId, boolean online, Instant observedAt) {
        presence.put(Objects.requireNonNull(playerId), new Presence(online, Objects.requireNonNull(observedAt)));
    }

    public CompletableFuture<OperationResult> claimLeadership(UUID claimantId) {
        Objects.requireNonNull(claimantId, "claimantId");
        OperationResult ready = mutationReady();
        if (!ready.success()) return CompletableFuture.completedFuture(ready);
        Member claimant = stateCache.snapshot().member(claimantId);
        if (claimant == null) return CompletableFuture.completedFuture(OperationResult.denied("You do not belong to a civilization."));
        return executeLocked(claimant.civilizationId(), connection -> claimLeadershipTransaction(
            connection, claimant.civilizationId(), claimantId),
            error -> OperationResult.denied(failureMessage("claim leadership", error)));
    }

    private TxOutcome<OperationResult> claimLeadershipTransaction(Connection connection, long civilizationId,
                                                                  UUID claimantId) throws Exception {
        LockedCivilization civilization = lockCivilization(connection, civilizationId);
        if (!active(civilization)) return unchanged(OperationResult.denied("The civilization is not active."));
        LockedMember leader = lockMember(connection, civilizationId, civilization.leaderId());
        LockedMember claimant = lockMember(connection, civilizationId, claimantId);
        if (claimant == null) return unchanged(OperationResult.denied("You no longer belong to this civilization."));
        if (claimant.role() == Role.LEADER) return unchanged(OperationResult.denied("You are already the leader."));
        if (leader == null || leader.role() != Role.LEADER)
            return unchanged(OperationResult.denied("The current leadership cannot be verified; ask an administrator to inspect it."));
        if (leader.membershipLocked() || claimant.membershipLocked() || civilization.currentWarId() != null)
            return unchanged(OperationResult.denied("Leadership transfer is frozen during a campaign."));
        int advisors = lockAdvisorCount(connection, civilizationId);
        Instant now = clock.instant();
        Presence observed = presence.get(leader.playerId());
        Instant lastSeen = leader.lastActiveAt();
        if (observed != null && observed.observedAt().isAfter(lastSeen)) lastSeen = observed.observedAt();
        OperationResult eligibility = LifecyclePolicy.validateLeadershipClaim(claimant.role(), advisors,
            observed != null && observed.online(), lastSeen, now);
        if (!eligibility.success()) return unchanged(eligibility);

        try (PreparedStatement statement = Sql.prepare(connection,
            "UPDATE civilizations SET leader_uuid = ?, row_version = row_version + 1 WHERE id = ?",
            claimantId, civilizationId)) {
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Leadership claim failed");
        }
        try (PreparedStatement statement = Sql.prepare(connection, """
            UPDATE civ_members SET role = CASE WHEN player_uuid = ? THEN 'LEADER' ELSE 'CITIZEN' END,
                last_active_at = CASE WHEN player_uuid = ? THEN GREATEST(last_active_at, ?) ELSE last_active_at END
            WHERE civ_id = ? AND player_uuid IN (?, ?)
            """, claimantId, claimantId, now, civilizationId, leader.playerId(), claimantId)) {
            if (statement.executeUpdate() != 2) throw new IllegalStateException("Leadership roles could not be updated");
        }
        AuditLog.write(connection, civilizationId, claimantId, "civilization.leadership_claimed", "player",
            leader.playerId().toString(), Map.of("formerLeaderName", leader.lastKnownName(),
                "newLeaderName", claimant.lastKnownName(), "formerLeaderRole", Role.CITIZEN.name(),
                "leaderLastSeen", lastSeen.toString(), "claimantRole", claimant.role().name()), settings.serverId());
        return changed(OperationResult.ok("You are now the leader of " + civilization.name()
            + ". " + leader.lastKnownName() + " is now a citizen."));
    }

    public CompletableFuture<DisbandResult> disband(UUID leaderId, String typedCivilizationName) {
        Objects.requireNonNull(leaderId, "leaderId");
        OperationResult ready = mutationReady();
        if (!ready.success()) return CompletableFuture.completedFuture(DisbandResult.denied(ready.message()));
        StateSnapshot snapshot = stateCache.snapshot();
        Member leader = snapshot.member(leaderId);
        if (leader == null || leader.role() != Role.LEADER) {
            return CompletableFuture.completedFuture(DisbandResult.denied("Only the civilization leader may disband it."));
        }
        Civilization civilization = snapshot.civilization(leader.civilizationId());
        if (civilization == null) return CompletableFuture.completedFuture(DisbandResult.denied("The civilization does not exist."));
        if (!confirmationMatches(typedCivilizationName, civilization.name())) {
            return CompletableFuture.completedFuture(DisbandResult.denied("The disband confirmation no longer matches this civilization."));
        }
        if (civilization.warLocked() || snapshot.warFor(civilization.id()) != null) {
            return CompletableFuture.completedFuture(DisbandResult.denied("A civilization cannot disband during a campaign."));
        }
        return executeLocked(civilization.id(), connection -> disbandTransaction(connection, civilization.id(), leaderId,
            typedCivilizationName), error -> DisbandResult.denied(failureMessage("disband the civilization", error)));
    }

    private TxOutcome<DisbandResult> disbandTransaction(Connection connection, long civilizationId, UUID leaderId,
                                                        String typedCivilizationName) throws Exception {
        Instant now = clock.instant();
        LockedCivilization civilization = lockCivilization(connection, civilizationId);
        if (!active(civilization)) return unchanged(DisbandResult.denied("The civilization is not active."));
        LockedMember leader = lockMember(connection, civilizationId, leaderId);
        if (leader == null || leader.role() != Role.LEADER || !leaderId.equals(civilization.leaderId())) {
            return unchanged(DisbandResult.denied("Only the civilization leader may disband it."));
        }
        if (!confirmationMatches(typedCivilizationName, civilization.name())) {
            return unchanged(DisbandResult.denied("The disband confirmation no longer matches this civilization."));
        }
        if (civilization.currentWarId() != null || lockCurrentWar(connection, civilizationId)) {
            return unchanged(DisbandResult.denied("A civilization cannot disband during a campaign."));
        }

        List<LockedMember> members = lockAllMembers(connection, civilizationId);
        int claims = lockAllClaimIds(connection, civilizationId).size();
        int invitations;
        int research;
        for (LockedMember member : members) archiveMembership(connection, member, now, "DISBAND");

        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE player_settings p JOIN civ_members m ON m.player_uuid = p.player_uuid
            SET p.chat_mode = FALSE, p.updated_at = ? WHERE m.civ_id = ? AND p.chat_mode = TRUE
            """)) {
            Sql.bind(statement, now, civilizationId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM civ_invites WHERE civ_id = ?")) {
            statement.setLong(1, civilizationId);
            invitations = statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE research_queue SET state = 'CANCELED', row_version = row_version + 1 WHERE civ_id = ? AND state = 'ACTIVE'")) {
            statement.setLong(1, civilizationId);
            research = statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            DELETE o FROM war_objectives o JOIN civ_claims c ON c.id = o.target_claim_id WHERE c.civ_id = ?
            """)) {
            statement.setLong(1, civilizationId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE economy_operations e JOIN civ_claims c ON c.id = e.claim_id
            SET e.claim_id = NULL WHERE c.civ_id = ?
            """)) {
            statement.setLong(1, civilizationId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM civ_claims WHERE civ_id = ?")) {
            statement.setLong(1, civilizationId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM civ_members WHERE civ_id = ?")) {
            statement.setLong(1, civilizationId);
            statement.executeUpdate();
        }

        BigDecimal payout = civilization.treasury();
        UUID recoveryOperationId = null;
        if (payout.signum() > 0) {
            recoveryOperationId = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economy_operations(operation_id, operation_type, player_uuid, beneficiary_uuid, civ_id,
                    amount, state, retry_count, context_json, created_at, updated_at)
                VALUES (?, 'DISBAND_PAYOUT', ?, ?, ?, ?, 'PENDING_DELIVERY', 0, ?, ?, ?)
                """)) {
                Sql.bind(statement, recoveryOperationId, leaderId, leaderId, civilizationId, payout,
                    JsonData.object(Map.of("civilizationName", civilization.name(), "reason", "DISBAND")), now, now);
                statement.executeUpdate();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE civilizations SET status = 'DISBANDED', disbanded_at = ?, treasury_balance = 0.00,
                current_war_id = NULL, row_version = row_version + 1 WHERE id = ?
            """)) {
            Sql.bind(statement, now, civilizationId);
            statement.executeUpdate();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", civilization.name());
        metadata.put("membersEnded", members.size());
        metadata.put("claimsReleased", claims);
        metadata.put("invitationsRemoved", invitations);
        metadata.put("researchCanceled", research);
        metadata.put("treasuryPayout", payout.toPlainString());
        if (recoveryOperationId != null) metadata.put("payoutRecoveryOperationId", recoveryOperationId.toString());
        AuditLog.write(connection, civilizationId, leaderId, "civilization.disbanded", "civilization",
            Long.toString(civilizationId), metadata, settings.serverId());
        return changed(new DisbandResult(OperationResult.ok("Civilization " + civilization.name() + " was archived and disbanded."),
            civilizationId, payout, recoveryOperationId));
    }

    public CompletableFuture<ActivityResult> updateActivity(ActivityUpdate update) {
        Objects.requireNonNull(update, "update");
        OperationResult ready = mutationReady();
        if (!ready.success()) return CompletableFuture.completedFuture(new ActivityResult(ready, false, false, null, 0));
        OperationResult name = LifecyclePolicy.validatePlayerName(update.lastKnownName());
        if (!name.success()) return CompletableFuture.completedFuture(new ActivityResult(name, false, false, null, 0));
        OperationResult observation = LifecyclePolicy.validateActivityObservation(
            update.observedSince(), update.observedAt(), clock.instant());
        if (!observation.success()) return CompletableFuture.completedFuture(new ActivityResult(observation, false, false, null, 0));
        Member cached = stateCache.snapshot().member(update.playerId());
        if (cached == null) {
            return CompletableFuture.completedFuture(new ActivityResult(OperationResult.denied("The player is not a civilization member."),
                false, false, null, 0));
        }
        return executeLocked(cached.civilizationId(), connection -> activityTransaction(connection, cached.civilizationId(), update),
            error -> new ActivityResult(OperationResult.denied(failureMessage("update member activity", error)),
                cached.established(), false, cached.lastActiveAt(), cached.eligiblePlaytimeSeconds()));
    }

    private TxOutcome<ActivityResult> activityTransaction(Connection connection, long civilizationId,
                                                          ActivityUpdate update) throws Exception {
        Instant now = clock.instant();
        OperationResult observation = LifecyclePolicy.validateActivityObservation(
            update.observedSince(), update.observedAt(), now);
        if (!observation.success()) return unchanged(new ActivityResult(observation, false, false, null, 0));
        LockedCivilization civilization = lockCivilization(connection, civilizationId);
        if (!active(civilization)) {
            return unchanged(new ActivityResult(OperationResult.denied("The civilization is not active."), false, false, null, 0));
        }
        LockedMember member = lockMember(connection, civilizationId, update.playerId());
        if (member == null) {
            return unchanged(new ActivityResult(OperationResult.denied("The player is not a civilization member."), false, false, null, 0));
        }
        Instant lastActive = member.lastActiveAt().isAfter(update.observedAt()) ? member.lastActiveAt() : update.observedAt();
        long observedDelta = LifecyclePolicy.unrecordedActivitySeconds(
            update.observedSince(), update.observedAt(), member.lastActiveAt());
        java.time.LocalDate gameDate = now.atZone(settings.war().zone()).toLocalDate();
        if (observedDelta > 0) {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO member_activity_daily(civ_id, player_uuid, activity_date, active_seconds)
                VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE active_seconds = active_seconds + VALUES(active_seconds)
                """)) {
                Sql.bind(statement, civilizationId, update.playerId(), java.sql.Date.valueOf(gameDate), observedDelta);
                statement.executeUpdate();
            }
        }
        long activeWindowSeconds;
        java.time.LocalDate cutoff = gameDate.minusDays(Math.max(0, settings.membership().establishedWindow().toDays() - 1));
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT COALESCE(SUM(active_seconds), 0) FROM member_activity_daily
            WHERE civ_id = ? AND player_uuid = ? AND activity_date >= ?
            """)) {
            Sql.bind(statement, civilizationId, update.playerId(), java.sql.Date.valueOf(cutoff));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                activeWindowSeconds = result.getLong(1);
            }
        }
        boolean established = LifecyclePolicy.established(member.joinedAt(), lastActive, activeWindowSeconds, now,
            settings.membership().establishedAfter(), settings.membership().establishedWindow(), settings.membership().establishedActive());
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE civ_members SET last_known_name = ?, last_active_at = ?, eligible_playtime_seconds = ?,
                established = ?, established_checked_at = ? WHERE civ_id = ? AND player_uuid = ?
            """)) {
            Sql.bind(statement, update.lastKnownName(), lastActive, activeWindowSeconds, established, now,
                civilizationId, update.playerId());
            statement.executeUpdate();
        }
        upsertPlayerSettings(connection, update.playerId(), update.lastKnownName(), false, now, true);
        boolean changedEstablishment = established != member.established();
        AuditLog.write(connection, civilizationId, update.playerId(), "membership.activity_updated", "player",
            update.playerId().toString(), Map.of("activeWindowSeconds", activeWindowSeconds,
                "lastActiveAt", lastActive.toString(), "established", established,
                "establishmentChanged", changedEstablishment), settings.serverId());
        ActivityResult result = new ActivityResult(OperationResult.ok(changedEstablishment
            ? "Member activity updated; establishment status changed." : "Member activity updated."), established,
            changedEstablishment, lastActive, activeWindowSeconds);
        // Last-active/playtime display can wait for the periodic snapshot refresh. Only an
        // establishment transition changes authorization/capacity and needs an immediate,
        // fail-closed cache rebuild. This avoids one full snapshot load per online player.
        return changedEstablishment ? changed(result) : unchanged(result);
    }

    public CompletableFuture<ChatPreference> chatPreference(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!database.healthy()) return CompletableFuture.completedFuture(new ChatPreference(playerId, false, "", Instant.EPOCH));
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT last_known_name, chat_mode, updated_at FROM player_settings WHERE player_uuid = ?")) {
                statement.setBytes(1, UuidBytes.toBytes(playerId));
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) return new ChatPreference(playerId, result.getBoolean(2), result.getString(1),
                        TimeUtil.instant(result.getTimestamp(3)));
                }
            }
            return new ChatPreference(playerId, false, "", Instant.EPOCH);
        }).exceptionally(ignored -> new ChatPreference(playerId, false, "", Instant.EPOCH));
    }

    public CompletableFuture<OperationResult> setCivilizationChat(UUID playerId, String lastKnownName, boolean enabled) {
        return updateChatPreference(playerId, lastKnownName, enabled, false);
    }

    public CompletableFuture<OperationResult> toggleCivilizationChat(UUID playerId, String lastKnownName) {
        return updateChatPreference(playerId, lastKnownName, false, true);
    }

    private CompletableFuture<OperationResult> updateChatPreference(UUID playerId, String lastKnownName,
                                                                    boolean enabled, boolean toggle) {
        Objects.requireNonNull(playerId, "playerId");
        OperationResult ready = mutationReady();
        if (!ready.success()) return CompletableFuture.completedFuture(ready);
        OperationResult name = LifecyclePolicy.validatePlayerName(lastKnownName);
        if (!name.success()) return CompletableFuture.completedFuture(name);
        Member cached = stateCache.snapshot().member(playerId);
        if (cached == null) return CompletableFuture.completedFuture(OperationResult.denied("Join a civilization before enabling civilization chat."));
        return executeLocked(cached.civilizationId(), connection -> chatPreferenceTransaction(connection, cached.civilizationId(),
            playerId, lastKnownName, enabled, toggle), error -> OperationResult.denied(failureMessage("save chat preference", error)));
    }

    private TxOutcome<OperationResult> chatPreferenceTransaction(Connection connection, long civilizationId, UUID playerId,
                                                                 String lastKnownName, boolean enabled, boolean toggle) throws Exception {
        Instant now = clock.instant();
        LockedCivilization civilization = lockCivilization(connection, civilizationId);
        if (!active(civilization)) return unchanged(OperationResult.denied("The civilization is not active."));
        LockedMember member = lockMember(connection, civilizationId, playerId);
        if (member == null) return unchanged(OperationResult.denied("Join a civilization before enabling civilization chat."));
        LockedPreference preference = lockPreference(connection, playerId);
        boolean next = toggle ? preference == null || !preference.chatMode() : enabled;
        boolean changed = preference == null || preference.chatMode() != next || !lastKnownName.equals(preference.lastKnownName());
        if (!changed) return unchanged(OperationResult.ok("Civilization chat is already " + (next ? "enabled." : "disabled.")));
        upsertPlayerSettings(connection, playerId, lastKnownName, next, now, false);
        AuditLog.write(connection, civilizationId, playerId, "player.civilization_chat_changed", "player", playerId.toString(),
            Map.of("enabled", next), settings.serverId());
        // Chat preference is not part of the authorization snapshot.
        return unchanged(OperationResult.ok("Civilization chat " + (next ? "enabled." : "disabled.")));
    }

    private CivilizationInfo toInfo(Civilization civilization) {
        StateSnapshot snapshot = stateCache.snapshot();
        List<MemberInfo> memberInfo = snapshot.members(civilization.id()).stream()
            .sorted(Comparator.comparingInt((Member member) -> roleRank(member.role())).reversed()
                .thenComparing(Member::lastKnownName, String.CASE_INSENSITIVE_ORDER))
            .map(member -> new MemberInfo(member.playerId(), member.lastKnownName(), member.role(), member.joinedAt(),
                member.lastActiveAt(), member.eligiblePlaytimeSeconds(), member.established(), member.contributionTotal(),
                member.membershipLocked()))
            .toList();
        int established = snapshot.establishedNonLeaders(civilization.id());
        int advisors = (int) memberInfo.stream().filter(member -> member.role() == Role.ADVISOR).count();
        return new CivilizationInfo(civilization.id(), civilization.name(), civilization.status(),
            civilization.leaderId(), civilization.createdAt(), civilization.peaceShieldUntil(), civilization.currentWarId(),
            snapshot.claims(civilization.id()).size(), claimCapacity(snapshot, civilization), established, advisors,
            advisorLimit(snapshot, civilization.id()), civilization.treasury(), civilization.knowledge(), memberInfo);
    }

    private int claimCapacity(StateSnapshot snapshot, Civilization civilization) {
        long capacity = settings.claims().baseCapacity()
            + (long) snapshot.establishedNonLeaders(civilization.id()) * settings.claims().establishedMemberBonus()
            + civilization.adminClaimBonus();
        for (String technologyKey : snapshot.technologies(civilization.id())) {
            TechnologyDefinition definition = technologies.get(technologyKey);
            if (definition != null) capacity += Math.max(0, definition.modifiers().getOrDefault("claim-capacity", 0));
        }
        return (int) Math.max(0, Math.min(settings.claims().absoluteCap(), capacity));
    }

    private int advisorLimit(StateSnapshot snapshot, long civilizationId) {
        List<Map<String, Integer>> modifiers = snapshot.technologies(civilizationId).stream()
            .map(technologies::get).filter(Objects::nonNull).map(TechnologyDefinition::modifiers).toList();
        return LifecyclePolicy.advisorLimit(settings.membership().baseAdvisorLimit(), modifiers);
    }

    private int advisorLimit(Connection connection, long civilizationId) throws Exception {
        List<Map<String, Integer>> modifiers = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT technology_key FROM civ_technologies WHERE civ_id = ? FOR UPDATE")) {
            statement.setLong(1, civilizationId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    TechnologyDefinition definition = technologies.get(result.getString(1));
                    if (definition != null) modifiers.add(definition.modifiers());
                }
            }
        }
        return LifecyclePolicy.advisorLimit(settings.membership().baseAdvisorLimit(), modifiers);
    }

    private OperationResult validateCreateRequest(CreateRequest request) {
        OperationResult playerName = LifecyclePolicy.validatePlayerName(request.founderName());
        if (!playerName.success()) return playerName;
        if (request.worldName().isBlank() || request.worldName().length() > 128) {
            return OperationResult.denied("The founding world is invalid.");
        }
        if (!request.capitalChunk().worldId().equals(request.home().worldId())) {
            return OperationResult.denied("The home location must be in the founding world.");
        }
        if (!Double.isFinite(request.home().x()) || !Double.isFinite(request.home().y())
            || !Double.isFinite(request.home().z()) || !Float.isFinite(request.home().yaw())
            || !Float.isFinite(request.home().pitch())) {
            return OperationResult.denied("The capital home location is invalid.");
        }
        if (!settings.worlds().allowed(request.worldName())) return OperationResult.denied("Civilizations cannot be founded in this world.");
        if (request.externallyProtected()) return OperationResult.denied("This chunk is inside a protected region.");
        if (request.biomeKey() != null && settings.worlds().blacklistedBiomes().contains(request.biomeKey().toUpperCase(Locale.ROOT))) {
            return OperationResult.denied("Civilizations cannot be founded in this biome.");
        }
        return LifecyclePolicy.validateFoundingPayment(request.inventory(), settings.founding().materialCost(),
            request.availableMoney(), settings.founding().moneyCost());
    }

    /**
     * Locks the Vault charge so its attachment to the new civilization commits
     * in the same MySQL transaction as the civilization itself.
     */
    private OperationResult lockAndValidateFoundingCharge(Connection connection, CreateRequest request) throws Exception {
        boolean chargeRequired = settings.economyMode() == Settings.EconomyMode.VAULT
            && settings.founding().moneyCost().signum() > 0;
        if (request.foundingOperationId() == null) {
            return chargeRequired ? OperationResult.denied("A confirmed founding payment is required.")
                : OperationResult.ok("No founding payment is required.");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT player_uuid, amount, state, civ_id FROM economy_operations
            WHERE operation_id = ? AND operation_type = 'CIVILIZATION_FOUNDING' FOR UPDATE
            """)) {
            Sql.bind(statement, request.foundingOperationId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return OperationResult.denied("The founding payment record does not exist.");
                UUID payer = UuidBytes.fromBytes(result.getBytes("player_uuid"));
                BigDecimal amount = result.getBigDecimal("amount");
                String state = result.getString("state");
                long existingCivilization = result.getLong("civ_id");
                boolean alreadyAttached = !result.wasNull();
                if (!request.founderId().equals(payer) || amount.compareTo(settings.founding().moneyCost()) != 0) {
                    return OperationResult.denied("The founding payment does not match this request.");
                }
                if (alreadyAttached) {
                    return OperationResult.denied("The founding payment is already attached to civilization "
                        + existingCivilization + ".");
                }
                if (!"EXTERNAL_APPLIED".equals(state)) {
                    return OperationResult.denied("The founding payment is not confirmed (state " + state + ").");
                }
                return OperationResult.ok("Founding payment confirmed.");
            }
        }
    }

    private boolean identityTaken(Connection connection, String normalizedName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id FROM civilizations
            WHERE normalized_name = ?
            LIMIT 1 FOR UPDATE
            """)) {
            Sql.bind(statement, normalizedName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean foundingAreaOccupied(Connection connection, ChunkKey chunk, int minimumDistance) throws Exception {
        int radius = Math.max(0, minimumDistance - 1);
        int minX = clampToInt((long) chunk.x() - radius);
        int maxX = clampToInt((long) chunk.x() + radius);
        int minZ = clampToInt((long) chunk.z() - radius);
        int maxZ = clampToInt((long) chunk.z() + radius);
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id FROM civ_claims
            WHERE world_uuid = ? AND chunk_x BETWEEN ? AND ? AND chunk_z BETWEEN ? AND ?
            LIMIT 1 FOR UPDATE
            """)) {
            Sql.bind(statement, chunk.worldId(), minX, maxX, minZ, maxZ);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private LockedCivilization lockCivilization(Connection connection, long civilizationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, name, leader_uuid, status, current_war_id, treasury_balance, created_at
            FROM civilizations WHERE id = ? FOR UPDATE
            """)) {
            statement.setLong(1, civilizationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                long warId = result.getLong("current_war_id");
                Long currentWarId = result.wasNull() ? null : warId;
                return new LockedCivilization(result.getLong("id"), result.getString("name"),
                    UuidBytes.get(result, "leader_uuid"), CivilizationStatus.valueOf(result.getString("status")),
                    currentWarId, result.getBigDecimal("treasury_balance"),
                    TimeUtil.instant(result.getTimestamp("created_at")));
            }
        }
    }

    private LockedMember lockMember(Connection connection, long civilizationId, UUID playerId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT civ_id, player_uuid, last_known_name, role, joined_at, last_active_at,
                eligible_playtime_seconds, established, membership_locked
            FROM civ_members WHERE civ_id = ? AND player_uuid = ? FOR UPDATE
            """)) {
            Sql.bind(statement, civilizationId, playerId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readLockedMember(result) : null;
            }
        }
    }

    private LockedMember lockMembershipAnywhere(Connection connection, UUID playerId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT civ_id, player_uuid, last_known_name, role, joined_at, last_active_at,
                eligible_playtime_seconds, established, membership_locked
            FROM civ_members WHERE player_uuid = ? FOR UPDATE
            """)) {
            statement.setBytes(1, UuidBytes.toBytes(playerId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readLockedMember(result) : null;
            }
        }
    }

    private LockedMember readLockedMember(ResultSet result) throws Exception {
        return new LockedMember(result.getLong("civ_id"), UuidBytes.get(result, "player_uuid"),
            result.getString("last_known_name"), Role.valueOf(result.getString("role")),
            TimeUtil.instant(result.getTimestamp("joined_at")), TimeUtil.instant(result.getTimestamp("last_active_at")),
            result.getLong("eligible_playtime_seconds"), result.getBoolean("established"),
            result.getBoolean("membership_locked"));
    }

    private LockedInvitation lockInvitation(Connection connection, long civilizationId, UUID targetId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, target_name, inviter_uuid, created_at, expires_at
            FROM civ_invites WHERE civ_id = ? AND target_uuid = ? AND status = 'ACTIVE' FOR UPDATE
            """)) {
            Sql.bind(statement, civilizationId, targetId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new LockedInvitation(result.getLong("id"), result.getString("target_name"),
                    UuidBytes.get(result, "inviter_uuid"), TimeUtil.instant(result.getTimestamp("created_at")),
                    TimeUtil.instant(result.getTimestamp("expires_at")));
            }
        }
    }

    private Instant latestCooldown(Connection connection, UUID playerId, boolean lock) throws Exception {
        String suffix = lock ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT cooldown_until FROM player_membership_history
            WHERE player_uuid = ? ORDER BY left_at DESC, id DESC LIMIT 1
            """ + suffix)) {
            statement.setBytes(1, UuidBytes.toBytes(playerId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? TimeUtil.instant(result.getTimestamp(1)) : null;
            }
        }
    }

    private int lockAdvisorCount(Connection connection, long civilizationId) throws Exception {
        int count = 0;
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT player_uuid FROM civ_members WHERE civ_id = ? AND role = 'ADVISOR' FOR UPDATE")) {
            statement.setLong(1, civilizationId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) count++;
            }
        }
        return count;
    }

    private Set<Long> lockPrivatePlotIds(Connection connection, long civilizationId, UUID playerId) throws Exception {
        Set<Long> ids = new java.util.LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM civ_claims WHERE civ_id = ? AND plot_owner_uuid = ? FOR UPDATE")) {
            Sql.bind(statement, civilizationId, playerId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) ids.add(result.getLong(1));
            }
        }
        return Set.copyOf(ids);
    }

    private int terminateMemberProperty(Connection connection, long civilizationId, UUID playerId) throws Exception {
        int count = lockPrivatePlotIds(connection, civilizationId, playerId).size();
        try (PreparedStatement statement = connection.prepareStatement("""
            DELETE t FROM plot_trust t JOIN civ_claims c ON c.id = t.claim_id
            WHERE c.civ_id = ? AND (t.granted_by = ? OR c.plot_owner_uuid = ?)
            """)) {
            Sql.bind(statement, civilizationId, playerId, playerId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE civ_claims SET plot_type = 'CIVIC', plot_owner_uuid = NULL, listing_kind = NULL,
                listing_seller_uuid = NULL, listing_price = NULL, original_purchase_price = NULL,
                plot_flags = NULL, home_label = NULL, greeting = NULL, listed_at = NULL,
                purchased_at = NULL, purchased_by = NULL, row_version = row_version + 1
            WHERE civ_id = ? AND plot_owner_uuid = ?
            """)) {
            Sql.bind(statement, civilizationId, playerId);
            statement.executeUpdate();
        }
        return count;
    }

    private void archiveMembership(Connection connection, LockedMember member, Instant now, String reason) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO player_membership_history(player_uuid, civ_id, joined_at, left_at, reason, cooldown_until)
            VALUES (?, ?, ?, ?, ?, ?)
            """)) {
            Sql.bind(statement, member.playerId(), member.civilizationId(), member.joinedAt(), now, reason,
                LifecyclePolicy.cooldownUntil(now, settings.membership().changeCooldown()));
            statement.executeUpdate();
        }
    }

    private void deleteMembership(Connection connection, long civilizationId, UUID playerId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM civ_members WHERE civ_id = ? AND player_uuid = ?")) {
            Sql.bind(statement, civilizationId, playerId);
            statement.executeUpdate();
        }
    }

    private void disableChatPreference(Connection connection, UUID playerId, Instant now) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE player_settings SET chat_mode = FALSE, updated_at = ? WHERE player_uuid = ? AND chat_mode = TRUE")) {
            Sql.bind(statement, now, playerId);
            statement.executeUpdate();
        }
    }

    private void upsertPlayerSettings(Connection connection, UUID playerId, String lastKnownName, boolean chatMode,
                                      Instant now, boolean preserveExistingChat) throws Exception {
        String updateChat = preserveExistingChat ? "chat_mode = chat_mode" : "chat_mode = VALUES(chat_mode)";
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO player_settings(player_uuid, last_known_name, chat_mode, notifications, locale, updated_at)
            VALUES (?, ?, ?, NULL, NULL, ?)
            ON DUPLICATE KEY UPDATE last_known_name = VALUES(last_known_name),
            """ + updateChat + ", updated_at = VALUES(updated_at)")) {
            Sql.bind(statement, playerId, lastKnownName, chatMode, now);
            statement.executeUpdate();
        }
    }

    private LockedPreference lockPreference(Connection connection, UUID playerId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT last_known_name, chat_mode, updated_at FROM player_settings WHERE player_uuid = ? FOR UPDATE")) {
            statement.setBytes(1, UuidBytes.toBytes(playerId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new LockedPreference(result.getString(1), result.getBoolean(2), TimeUtil.instant(result.getTimestamp(3)));
            }
        }
    }

    private boolean lockCurrentWar(Connection connection, long civilizationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id FROM wars
            WHERE (attacker_civ_id = ? OR defender_civ_id = ?) AND state IN ('PENDING', 'ACTIVE', 'RESOLVING')
            LIMIT 1 FOR UPDATE
            """)) {
            statement.setLong(1, civilizationId);
            statement.setLong(2, civilizationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private List<LockedMember> lockAllMembers(Connection connection, long civilizationId) throws Exception {
        List<LockedMember> members = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT civ_id, player_uuid, last_known_name, role, joined_at, last_active_at,
                eligible_playtime_seconds, established, membership_locked
            FROM civ_members WHERE civ_id = ? ORDER BY player_uuid FOR UPDATE
            """)) {
            statement.setLong(1, civilizationId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) members.add(readLockedMember(result));
            }
        }
        return List.copyOf(members);
    }

    private List<Long> lockAllClaimIds(Connection connection, long civilizationId) throws Exception {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM civ_claims WHERE civ_id = ? ORDER BY id FOR UPDATE")) {
            statement.setLong(1, civilizationId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) ids.add(result.getLong(1));
            }
        }
        return List.copyOf(ids);
    }

    private OperationResult mutationReady() {
        if (!database.healthy()) return OperationResult.denied("Civilization storage is unavailable; no changes were made.");
        if (!stateCache.ready()) return OperationResult.denied("Civilization state is still loading; try again shortly.");
        return OperationResult.ok("Ready.");
    }

    private <T> CompletableFuture<T> executeLocked(long lockKey, TransactionWork<T> work,
                                                    Function<Throwable, T> failure) {
        CompletableFuture<TxOutcome<T>> transaction = database.transaction(connection ->
            locks.withLock(lockKey, () -> work.execute(connection)));
        return finishMutation(transaction).exceptionally(failure);
    }

    private <T> CompletableFuture<T> executeLocked(long firstLockKey, long secondLockKey, TransactionWork<T> work,
                                                    Function<Throwable, T> failure) {
        CompletableFuture<TxOutcome<T>> transaction = database.transaction(connection ->
            locks.withLocks(firstLockKey, secondLockKey, () -> work.execute(connection)));
        return finishMutation(transaction).exceptionally(failure);
    }

    private <T> CompletableFuture<T> finishMutation(CompletableFuture<TxOutcome<T>> transaction) {
        return transaction.thenCompose(outcome -> {
            if (!outcome.mutated()) return CompletableFuture.completedFuture(outcome.value());
            return stateCache.refreshAfterMutation().handle((ignored, refreshError) -> {
                return outcome.value();
            });
        });
    }

    private String failureMessage(String action, Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String detail = root.getMessage();
        return "Could not " + action + "; no database changes were committed"
            + (detail == null || detail.isBlank() ? "." : ": " + detail);
    }

    private static boolean active(LockedCivilization civilization) {
        return civilization != null && civilization.status() == CivilizationStatus.ACTIVE;
    }

    private static boolean confirmationMatches(String supplied, String actual) {
        return supplied != null && NameNormalizer.normalize(supplied).equals(NameNormalizer.normalize(actual));
    }

    private static long playerLockKey(UUID playerId) {
        long mixed = playerId.getMostSignificantBits() ^ Long.rotateLeft(playerId.getLeastSignificantBits(), 23);
        return Long.MIN_VALUE | (mixed & Long.MAX_VALUE);
    }

    private static int clampToInt(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    private static int roleRank(Role role) {
        return switch (role) {
            case LEADER -> 3;
            case ADVISOR -> 2;
            case CITIZEN -> 1;
        };
    }

    private static <T> TxOutcome<T> changed(T value) {
        return new TxOutcome<>(value, true);
    }

    private static <T> TxOutcome<T> unchanged(T value) {
        return new TxOutcome<>(value, false);
    }

    @FunctionalInterface
    private interface TransactionWork<T> {
        TxOutcome<T> execute(Connection connection) throws Exception;
    }

    private record TxOutcome<T>(T value, boolean mutated) {}

    private record Presence(boolean online, Instant observedAt) {}

    private record LockedCivilization(long id, String name, UUID leaderId, CivilizationStatus status,
                                      Long currentWarId, BigDecimal treasury, Instant createdAt) {}

    private record LockedMember(long civilizationId, UUID playerId, String lastKnownName, Role role, Instant joinedAt,
                                Instant lastActiveAt, long activeWindowSeconds, boolean established,
                                boolean membershipLocked) {}

    private record LockedInvitation(long id, String targetName, UUID inviterId, Instant createdAt, Instant expiresAt) {}

    private record LockedPreference(String lastKnownName, boolean chatMode, Instant updatedAt) {}
}
