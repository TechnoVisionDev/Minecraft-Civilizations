package io.github.empireage.civilizations.service.lifecycle;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.concurrent.CivilizationLocks;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.config.TechnologyCatalog;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.database.SqlFunction;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.Role;
import io.github.empireage.civilizations.util.UuidBytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Executes the service transaction against JDBC fixtures with deliberately stale cache data. */
class LeadershipClaimTest {
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");
    private final UUID oldLeader = UUID.randomUUID();
    private final UUID claimant = UUID.randomUUID();
    private final UUID rival = UUID.randomUUID();
    private final Map<UUID, Row> members = new HashMap<>();
    private final List<String> writes = new ArrayList<>();
    private UUID currentLeader;
    private boolean war;
    private String status;
    private Database database;
    private StateCache cache;
    private StateSnapshot snapshot;
    private Connection connection;
    private Settings settings;
    private CivilizationLifecycleService lifecycle;

    @BeforeEach void setup() throws Exception {
        currentLeader = oldLeader;
        status = "ACTIVE";
        members.put(oldLeader, new Row(Role.LEADER, NOW.minus(Duration.ofDays(7)), false));
        members.put(claimant, new Row(Role.ADVISOR, NOW.minus(Duration.ofDays(10)), false));
        members.put(rival, new Row(Role.ADVISOR, NOW.minus(Duration.ofDays(10)), false));
        database = mock(Database.class);
        cache = mock(StateCache.class);
        snapshot = mock(StateSnapshot.class);
        settings = mock(Settings.class);
        when(settings.serverId()).thenReturn("test");
        when(database.healthy()).thenReturn(true);
        when(cache.ready()).thenReturn(true);
        when(cache.snapshot()).thenReturn(snapshot);
        when(cache.refreshAfterMutation()).thenReturn(CompletableFuture.completedFuture(snapshot));
        for (UUID id : members.keySet()) when(snapshot.member(id)).thenReturn(new Member(1, id, name(id),
            members.get(id).role(), Instant.EPOCH, Instant.EPOCH, 0, false, 0, false));
        connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenAnswer(call -> statement(call.getArgument(0)));
        when(database.transaction(any())).thenAnswer(call -> {
            SqlFunction<Connection, ?> work = call.getArgument(0);
            try { return CompletableFuture.completedFuture(work.apply(connection)); }
            catch (Exception e) { return CompletableFuture.failedFuture(e); }
        });
        lifecycle = service();
    }

    private CivilizationLifecycleService service() {
        return new CivilizationLifecycleService(database, cache, new CivilizationLocks(16), settings,
            mock(TechnologyCatalog.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void advisorCanClaimAtExactlySevenDaysAndFormerLeaderKeepsMembershipAsCitizen() {
        assertTrue(lifecycle.claimLeadership(claimant).join().success());
        assertEquals(claimant, currentLeader);
        assertEquals(Role.LEADER, members.get(claimant).role());
        assertEquals(NOW, members.get(claimant).lastActive());
        assertEquals(Role.CITIZEN, members.get(oldLeader).role());
        assertEquals(3, members.size());
        assertTrue(writes.stream().anyMatch(sql -> sql.contains("civ_audit_log")));
        assertTrue(writes.stream().noneMatch(sql -> sql.contains("civ_claims")));
        verify(cache).refreshAfterMutation();
    }

    @Test void citizenCanClaimOnlyWhenNoAdvisorsExistEvenIfAdvisorsAreOffline() {
        members.put(claimant, new Row(Role.CITIZEN, NOW, false));
        assertFalse(lifecycle.claimLeadership(claimant).join().success());
        assertTrue(writes.isEmpty());
        members.put(rival, new Row(Role.CITIZEN, NOW, false));
        assertTrue(lifecycle.claimLeadership(claimant).join().success());
        assertEquals(claimant, currentLeader);
    }

    @Test void savedActivitySurvivesServiceRestartAndBoundaryDoesNotRoundUp() {
        members.put(oldLeader, new Row(Role.LEADER, NOW.minus(Duration.ofDays(7)).plusSeconds(1), false));
        assertFalse(service().claimLeadership(claimant).join().success());
        assertTrue(writes.isEmpty());
        members.put(oldLeader, new Row(Role.LEADER, NOW.minus(Duration.ofDays(7)), false));
        assertTrue(service().claimLeadership(claimant).join().success());
    }

    @Test void onlineLeaderAndRecentLogoutBlockClaimsBeforeActivitySaveFinishes() {
        lifecycle.recordPresence(oldLeader, true, NOW.minus(Duration.ofDays(8)));
        assertFalse(lifecycle.claimLeadership(claimant).join().success());
        lifecycle.recordPresence(oldLeader, false, NOW.minusSeconds(1));
        assertFalse(lifecycle.claimLeadership(claimant).join().success());
        assertTrue(writes.isEmpty());
    }

    @Test void queuedClaimSeesLeaderReconnectBeforeTransactionExecutes() throws Exception {
        when(database.transaction(any())).thenAnswer(call -> {
            lifecycle.recordPresence(oldLeader, true, NOW);
            SqlFunction<Connection, ?> work = call.getArgument(0);
            return CompletableFuture.completedFuture(work.apply(connection));
        });
        assertFalse(lifecycle.claimLeadership(claimant).join().success());
        assertTrue(writes.isEmpty());
    }

    @Test void simultaneousClaimsHaveOnlyOneWinnerDespiteStaleCacheAndOldClaimantActivity() {
        when(database.transaction(any())).thenAnswer(call -> {
            SqlFunction<Connection, ?> work = call.getArgument(0);
            return CompletableFuture.supplyAsync(() -> {
                try { return work.apply(connection); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
        });
        var first = lifecycle.claimLeadership(claimant);
        var second = lifecycle.claimLeadership(rival);
        int winners = (first.join().success() ? 1 : 0) + (second.join().success() ? 1 : 0);
        assertEquals(1, winners);
        assertEquals(1, members.values().stream().filter(row -> row.role() == Role.LEADER).count());
        assertEquals(1, writes.stream().filter(sql -> sql.contains("UPDATE civilizations")).count());
        verify(cache).refreshAfterMutation();
    }

    @Test void staleMembershipCannotClaimAndAdvisorPriorityUsesCurrentDatabaseRoles() {
        members.remove(claimant);
        assertFalse(lifecycle.claimLeadership(claimant).join().success());
        members.put(claimant, new Row(Role.CITIZEN, NOW, false));
        assertFalse(lifecycle.claimLeadership(claimant).join().success());
        assertTrue(writes.isEmpty());
    }

    @Test void leaderCannotReclaimTheirOwnRole() {
        assertFalse(lifecycle.claimLeadership(oldLeader).join().success());
        assertTrue(writes.isEmpty());
    }

    @Test void campaignsAndMembershipLocksStillBlockClaims() {
        war = true;
        assertFalse(lifecycle.claimLeadership(claimant).join().success());
        war = false;
        members.put(claimant, new Row(Role.ADVISOR, NOW, true));
        assertFalse(lifecycle.claimLeadership(claimant).join().success());
        members.put(claimant, new Row(Role.ADVISOR, NOW, false));
        members.put(oldLeader, new Row(Role.LEADER, Instant.EPOCH, true));
        assertFalse(lifecycle.claimLeadership(claimant).join().success());
        assertTrue(writes.isEmpty());
    }

    @Test void inactiveCivilizationsAndMissingLeadersFailClosed() {
        status = "ARCHIVED";
        assertFalse(lifecycle.claimLeadership(claimant).join().success());
        status = "ACTIVE";
        members.remove(oldLeader);
        assertFalse(lifecycle.claimLeadership(claimant).join().success());
        assertTrue(writes.isEmpty());
    }

    @Test void unavailableStorageOrCacheAndNonmembersCannotStartClaims() {
        when(database.healthy()).thenReturn(false);
        assertFalse(lifecycle.claimLeadership(claimant).join().success());
        when(database.healthy()).thenReturn(true);
        when(cache.ready()).thenReturn(false);
        assertFalse(lifecycle.claimLeadership(claimant).join().success());
        when(cache.ready()).thenReturn(true);
        assertFalse(lifecycle.claimLeadership(UUID.randomUUID()).join().success());
        verify(database, never()).transaction(any());
    }

    private PreparedStatement statement(String sql) throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        Map<Integer, Object> parameters = new HashMap<>();
        doAnswer(call -> { parameters.put(call.getArgument(0), call.getArgument(1)); return null; })
            .when(statement).setObject(anyInt(), any());
        doAnswer(call -> { parameters.put(call.getArgument(0), UuidBytes.fromBytes(call.getArgument(1))); return null; })
            .when(statement).setBytes(anyInt(), any());
        doAnswer(call -> { parameters.put(call.getArgument(0), call.getArgument(1)); return null; })
            .when(statement).setLong(anyInt(), anyLong());
        doAnswer(call -> { parameters.put(call.getArgument(0), call.getArgument(1)); return null; })
            .when(statement).setTimestamp(anyInt(), any());
        when(statement.executeQuery()).thenAnswer(ignored -> {
            assertTrue(sql.contains("FOR UPDATE"), "eligibility must read locked rows");
            ResultSet result = mock(ResultSet.class);
            if (sql.contains("FROM civilizations")) {
                when(result.next()).thenReturn(true);
                when(result.getLong("id")).thenReturn(1L);
                when(result.getString("name")).thenReturn("Rome");
                when(result.getBytes("leader_uuid")).thenReturn(UuidBytes.toBytes(currentLeader));
                when(result.getString("status")).thenReturn(status);
                when(result.wasNull()).thenReturn(!war);
                when(result.getBigDecimal("treasury_balance")).thenReturn(BigDecimal.ZERO);
                when(result.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.EPOCH));
            } else if (sql.contains("role = 'ADVISOR'")) {
                var remaining = new java.util.concurrent.atomic.AtomicLong(members.values().stream()
                    .filter(row -> row.role() == Role.ADVISOR).count());
                when(result.next()).thenAnswer(call -> remaining.getAndDecrement() > 0);
            } else {
                UUID id = (UUID) parameters.get(2);
                Row row = members.get(id);
                when(result.next()).thenReturn(row != null);
                if (row != null) {
                    when(result.getLong("civ_id")).thenReturn(1L);
                    when(result.getBytes("player_uuid")).thenReturn(UuidBytes.toBytes(id));
                    when(result.getString("last_known_name")).thenReturn(name(id));
                    when(result.getString("role")).thenReturn(row.role().name());
                    when(result.getTimestamp("joined_at")).thenReturn(Timestamp.from(Instant.EPOCH));
                    when(result.getTimestamp("last_active_at")).thenReturn(Timestamp.from(row.lastActive()));
                    when(result.getBoolean("membership_locked")).thenReturn(row.locked());
                }
            }
            return result;
        });
        when(statement.executeUpdate()).thenAnswer(ignored -> {
            writes.add(sql);
            if (sql.contains("UPDATE civilizations")) currentLeader = (UUID) parameters.get(1);
            if (sql.contains("UPDATE civ_members")) {
                UUID winner = (UUID) parameters.get(1);
                UUID former = (UUID) parameters.get(5);
                members.put(winner, new Row(Role.LEADER, ((Timestamp) parameters.get(3)).toInstant(), false));
                members.put(former, new Row(Role.CITIZEN, members.get(former).lastActive(), false));
                return 2;
            }
            return 1;
        });
        return statement;
    }

    private String name(UUID id) { return id.equals(oldLeader) ? "FormerLeader" : id.equals(claimant) ? "Claimant" : "Rival"; }
    private record Row(Role role, Instant lastActive, boolean locked) {}
}
