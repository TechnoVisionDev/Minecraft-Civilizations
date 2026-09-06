package io.github.empireage.civilizations.service.economy;

import io.github.empireage.civilizations.cache.*;
import io.github.empireage.civilizations.concurrent.CivilizationLocks;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.database.*;
import io.github.empireage.civilizations.domain.*;
import io.github.empireage.civilizations.util.UuidBytes;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TreasuryAuthorizationTest {
    private final UUID actor = UUID.randomUUID();
    private final Database database = mock(Database.class);
    private final StateCache cache = mock(StateCache.class);
    private final EconomyService economy = mock(EconomyService.class);
    private final List<String> sql = new ArrayList<>();

    private TreasuryService service(boolean ready, UUID actualLeader, String actualRole) throws Exception {
        when(economy.enabled()).thenReturn(true);
        when(cache.ready()).thenReturn(ready);
        StateSnapshot snapshot = mock(StateSnapshot.class);
        when(cache.snapshot()).thenReturn(snapshot);
        when(snapshot.member(actor)).thenReturn(new Member(1, actor, "CachedLeader", Role.LEADER,
            Instant.EPOCH, Instant.EPOCH, 0, true, 0, false));
        Settings settings = mock(Settings.class);
        when(settings.serverId()).thenReturn("test");
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String query = call.getArgument(0);
            sql.add(query);
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet result = mock(ResultSet.class);
            when(statement.executeQuery()).thenReturn(result);
            when(statement.executeUpdate()).thenReturn(1);
            if (query.contains("SELECT treasury_balance")) {
                when(result.next()).thenReturn(true);
                when(result.getBigDecimal(1)).thenReturn(new BigDecimal("1000.00"));
            } else if (query.contains("SELECT c.leader_uuid")) {
                when(result.next()).thenReturn(actualRole != null);
                when(result.getBytes("leader_uuid")).thenReturn(UuidBytes.toBytes(actualLeader));
                when(result.getString("role")).thenReturn(actualRole);
            }
            return statement;
        });
        when(database.transaction(any())).thenAnswer(call -> {
            SqlFunction<Connection, Object> transaction = call.getArgument(0);
            return CompletableFuture.completedFuture(transaction.apply(connection));
        });
        TreasuryService service = spy(new TreasuryService(database, cache, new CivilizationLocks(16), settings, economy));
        doReturn(CompletableFuture.completedFuture(OperationResult.ok("Paid"))).when(service).resumeWithdrawal(any());
        return service;
    }

    @Test void unavailableAuthorizationCachePreventsAnyTransaction() throws Exception {
        assertFalse(service(false, actor, "LEADER").withdraw(actor, BigDecimal.TEN).join().success());
        verifyNoInteractions(database);
        assertTrue(sql.isEmpty());
    }

    @Test void staleLeaderAfterTransferCannotWithdraw() throws Exception {
        rejected(UUID.randomUUID(), "LEADER");
    }

    @Test void demotedMemberCannotWithdrawEvenIfLeaderColumnIsStale() throws Exception {
        rejected(actor, "CITIZEN");
    }

    @Test void departedLeaderCannotWithdraw() throws Exception {
        rejected(actor, null);
    }

    private void rejected(UUID leader, String role) throws Exception {
        TreasuryService service = service(true, leader, role);
        assertFalse(service.withdraw(actor, BigDecimal.TEN).join().success());
        assertTrue(sql.stream().anyMatch(query -> query.contains("c.leader_uuid") && query.contains("FOR UPDATE")));
        assertTrue(sql.stream().noneMatch(query -> query.stripLeading().startsWith("UPDATE") || query.stripLeading().startsWith("INSERT")));
        verify(service, never()).resumeWithdrawal(any());
    }

    @Test void currentLeaderIsAuthorizedBeforeReservingWithdrawal() throws Exception {
        TreasuryService service = service(true, actor, "LEADER");
        assertTrue(service.withdraw(actor, BigDecimal.TEN).join().success());
        int authorization = -1, debit = -1;
        for (int i = 0; i < sql.size(); i++) {
            if (sql.get(i).contains("SELECT c.leader_uuid")) authorization = i;
            if (sql.get(i).contains("UPDATE civilizations SET treasury_balance")) debit = i;
        }
        assertTrue(authorization >= 0 && debit > authorization);
        verify(service).resumeWithdrawal(any());
    }
}
