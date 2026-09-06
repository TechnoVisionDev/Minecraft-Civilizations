package io.github.empireage.civilizations.service.war;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.concurrent.CivilizationLocks;
import io.github.empireage.civilizations.config.CatalogManager;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.database.SqlFunction;
import io.github.empireage.civilizations.domain.*;
import io.github.empireage.civilizations.service.progression.StockpileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarServicePolicyTest {
    private final Instant start = Instant.parse("2026-09-05T21:00:00Z");
    private final Instant end = start.plus(Duration.ofHours(4));
    private final Database database = mock(Database.class);
    private final StateCache cache = mock(StateCache.class);
    private final Settings settings = mock(Settings.class, RETURNS_DEEP_STUBS);
    private final StockpileService stockpile = mock(StockpileService.class);
    private final Connection connection = mock(Connection.class);
    private final ResultSet row = mock(ResultSet.class);
    private final Map<String, PreparedStatement> statements = new LinkedHashMap<>();

    private WarService service(WarState state, Instant now) throws Exception {
        when(database.healthy()).thenReturn(true);
        when(settings.war().truce()).thenReturn(Duration.ofDays(7));
        when(settings.serverId()).thenReturn("test");
        when(cache.refreshAfterMutation()).thenReturn(CompletableFuture.completedFuture(null));
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            PreparedStatement statement = statements.computeIfAbsent(sql, ignored -> mock(PreparedStatement.class));
            if (sql.startsWith("SELECT * FROM wars")) when(statement.executeQuery()).thenReturn(row);
            return statement;
        });
        when(row.next()).thenReturn(true, false);
        when(row.getLong("id")).thenReturn(7L);
        when(row.getLong("attacker_civ_id")).thenReturn(1L);
        when(row.getLong("defender_civ_id")).thenReturn(2L);
        when(row.getString("state")).thenReturn(state.name());
        when(row.getTimestamp("scheduled_start")).thenReturn(Timestamp.from(start));
        when(row.getTimestamp("scheduled_end")).thenReturn(Timestamp.from(end));
        when(database.transaction(any())).thenAnswer(call -> {
            SqlFunction<Connection, Object> work = call.getArgument(0);
            return CompletableFuture.completedFuture(work.apply(connection));
        });
        return new WarService(database, cache, settings, mock(CatalogManager.class), stockpile,
            new CivilizationLocks(64), Clock.fixed(now, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @EnumSource(value = WarState.class, names = {"PENDING", "ACTIVE", "RESOLVING"})
    void expiredWindowsEndWithoutChangingLandOrStockpiles(WarState state) throws Exception {
        var result = service(state, end.plusSeconds(3600)).reconcileTransitions().join();
        assertEquals(Set.of(7L), result.resolved());
        PreparedStatement ending = statementContaining("result = 'WINDOW_ENDED'");
        assertTrue(statements.keySet().stream().anyMatch(sql -> sql.contains("winning_civ_id = NULL")));
        verify(ending).setTimestamp(1, Timestamp.from(end.plus(Duration.ofDays(7))));
        verify(ending).executeUpdate();
        verify(statementContaining("UPDATE war_objectives SET state = 'FAILED'")).executeUpdate();
        verify(statementContaining("UPDATE civilizations SET current_war_id = NULL")).executeUpdate();
        verify(statementContaining("UPDATE civ_members SET membership_locked = FALSE")).executeUpdate();
        assertFalse(statements.keySet().stream().anyMatch(sql -> sql.contains("civ_claims") || sql.contains("civ_stockpile")));
        verifyNoInteractions(stockpile);
    }

    @Test
    void activeWindowDoesNotEndEarly() throws Exception {
        var result = service(WarState.ACTIVE, end.minusNanos(1)).reconcileTransitions().join();
        assertTrue(result.resolved().isEmpty());
        assertEquals(1, statements.size());
        verifyNoInteractions(stockpile);
    }

    @Test
    void activationRecordsRosterWithoutCaptureObjectives() throws Exception {
        var result = service(WarState.PENDING, start).reconcileTransitions().join();
        assertEquals(Set.of(7L), result.activated());
        verify(statementContaining("INSERT IGNORE INTO war_roster")).executeUpdate();
        assertFalse(statements.keySet().stream().anyMatch(sql -> sql.contains("war_objectives")));
    }

    @Test
    void mutualPeaceEndsActiveWindowImmediatelyEvenWithLegacyObjectives() throws Exception {
        var service = service(WarState.ACTIVE, start.plusSeconds(10));
        UUID leader = UUID.randomUUID();
        War war = new War(7, 1, 2, WarState.ACTIVE, leader, start.minusSeconds(86400), start, end,
            ZoneOffset.UTC, start, start, null, false, true, Set.of(leader), Set.of());
        Member member = new Member(1, leader, "Leader", Role.LEADER, start, start, 0, true, 0, true);
        when(cache.snapshot()).thenReturn(new StateSnapshot(Map.of(), Map.of(), Map.of(leader, member), Map.of(),
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(7L, war), Map.of(1L, war), Map.of(), start));
        when(row.getBoolean("defender_peace")).thenReturn(true);
        assertTrue(service.peace(leader).join().success());
        verify(statementContaining("UPDATE wars SET state = 'CANCELLED'")).setString(1, "MUTUAL_PEACE");
        assertFalse(statements.keySet().stream().anyMatch(sql -> sql.contains("SELECT 1 FROM war_objectives")));
        verifyNoInteractions(stockpile);
    }

    private PreparedStatement statementContaining(String text) {
        return statements.entrySet().stream().filter(entry -> entry.getKey().contains(text))
            .map(Map.Entry::getValue).findFirst().orElseThrow();
    }
}
