package io.github.empireage.civilizations.service.religion;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.database.SqlFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReligionServiceTest {
    private final Instant now = Instant.parse("2026-09-07T12:00:00Z");
    private final UUID player = UUID.randomUUID();
    private Database database;
    private StateCache cache;
    private Connection connection;
    private PreparedStatement favorUpdate;
    private PreparedStatement cooldownUpdate;
    private ResultSet favor;
    private ResultSet cooldown;
    private IntUnaryOperator picker;

    @BeforeEach void setup() throws Exception {
        database = mock(Database.class);
        cache = mock(StateCache.class);
        when(cache.snapshot()).thenReturn(StateSnapshot.empty());
        connection = mock(Connection.class);
        favorUpdate = mock(PreparedStatement.class);
        cooldownUpdate = mock(PreparedStatement.class);
        when(favorUpdate.executeUpdate()).thenReturn(1);
        when(cooldownUpdate.executeUpdate()).thenReturn(1);
        favor = mock(ResultSet.class);
        when(favor.next()).thenReturn(true);
        when(favor.getInt(1)).thenReturn(1);
        cooldown = mock(ResultSet.class);
        when(cooldown.next()).thenReturn(true);
        when(cooldown.getTimestamp(1)).thenReturn(Timestamp.from(now));
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.contains("UPDATE deity_favor")) return favorUpdate;
            if (sql.contains("UPDATE sacrifice_cooldowns")) return cooldownUpdate;
            PreparedStatement statement = mock(PreparedStatement.class);
            if (sql.contains("SELECT favor_level")) when(statement.executeQuery()).thenReturn(favor);
            if (sql.contains("SELECT available_at")) when(statement.executeQuery()).thenReturn(cooldown);
            return statement;
        });
        when(database.transaction(any())).thenAnswer(call -> {
            SqlFunction<Connection, ?> work = call.getArgument(0);
            try { return CompletableFuture.completedFuture(work.apply(connection)); }
            catch (Exception e) { return CompletableFuture.failedFuture(e); }
        });
        picker = mock(IntUnaryOperator.class);
        when(picker.applyAsInt(anyInt())).thenReturn(1);
    }

    private ReligionService service() {
        return new ReligionService(database, cache, Duration.ofHours(4), "test", picker);
    }

    @Test void allLevelsUseInclusiveBoundsAndStrictlyExceedTheRoll() {
        int[] maxima = {30, 25, 20};
        for (int level = 1; level <= 3; level++) {
            int max = maxima[level - 1];
            assertEquals(max, ReligionService.maxRoll(level));
            assertFalse(ReligionService.qualifies(true, max, max, level));
            assertTrue(ReligionService.qualifies(true, max + 1, max, level));
            assertTrue(ReligionService.qualifies(true, 2, 1, level));
            assertFalse(ReligionService.qualifies(true, 1, 1, level));
            assertFalse(ReligionService.qualifies(false, 64, 1, level));
            int currentLevel = level;
            assertThrows(IllegalArgumentException.class, () -> ReligionService.qualifies(true, 64, max + 1, currentLevel));
        }
        assertThrows(IllegalArgumentException.class, () -> ReligionService.maxRoll(0));
        assertThrows(IllegalArgumentException.class, () -> ReligionService.maxRoll(4));
        assertThrows(IllegalArgumentException.class, () -> ReligionService.maxRoll(5));
        assertThrows(IllegalArgumentException.class, () -> ReligionService.qualifies(true, 64, 0, 1));
    }

    @Test void successUsesCurrentFavorThenAdvancesOnlyThatPlayerAndGod() throws Exception {
        for (int level = 1; level <= 3; level++) {
            when(favor.getInt(1)).thenReturn(level);
            var result = service().record(player, "zeus", "minecraft:gold_ingot", 64, true, now).join();
            assertTrue(result.recorded());
            assertTrue(result.success());
            assertEquals(Math.min(3, level + 1), result.favorLevel());
            assertEquals(now.plus(Duration.ofHours(4)), result.availableAt());
            verify(picker).applyAsInt(ReligionService.maxRoll(level));
        }
        verify(favorUpdate, times(2)).executeUpdate();
        verify(favorUpdate, times(2)).setBytes(2, io.github.empireage.civilizations.util.UuidBytes.toBytes(player));
        verify(favorUpdate, times(2)).setObject(3, "zeus");
    }

    @Test void wrongOfferingAndFailedRollNeverChangeFavorButConsumeCooldown() throws Exception {
        var wrong = service().record(player, "zeus", "minecraft:cod", 64, false, now).join();
        assertTrue(wrong.recorded());
        assertFalse(wrong.success());
        assertEquals(1, wrong.favorLevel());
        when(picker.applyAsInt(30)).thenReturn(30);
        var equal = service().record(player, "zeus", "minecraft:gold_ingot", 30, true, now).join();
        assertTrue(equal.recorded());
        assertFalse(equal.success());
        assertEquals(1, equal.favorLevel());
        verifyNoInteractions(favorUpdate);
        verify(cooldownUpdate, times(2)).executeUpdate();
    }

    @Test void cooldownDenialDoesNotRollOrGrantFavorAndAllowsItemRestoration() throws Exception {
        when(cooldown.getTimestamp(1)).thenReturn(Timestamp.from(now.plusSeconds(60)));
        var result = service().record(player, "zeus", "minecraft:gold_ingot", 64, true, now).join();
        assertFalse(result.recorded());
        assertTrue(result.safeToRestore());
        verifyNoInteractions(picker, favorUpdate, cooldownUpdate);
    }

    @Test void recoveryReturnsTheCommittedRollAndFavorWithoutRepeatingTheWrite() throws Exception {
        when(database.transaction(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("commit response lost")));
        PreparedStatement lookup = mock(PreparedStatement.class);
        ResultSet saved = mock(ResultSet.class);
        when(connection.prepareStatement(contains("WHERE c.operation_id"))).thenReturn(lookup);
        when(lookup.executeQuery()).thenReturn(saved);
        when(saved.next()).thenReturn(true);
        when(saved.getTimestamp(1)).thenReturn(Timestamp.from(now.plus(Duration.ofHours(4))));
        when(saved.getInt(2)).thenReturn(25);
        when(saved.getBoolean(3)).thenReturn(true);
        when(saved.getInt(4)).thenReturn(3);
        when(database.read(any())).thenAnswer(call -> {
            SqlFunction<Connection, ?> work = call.getArgument(0);
            return CompletableFuture.completedFuture(work.apply(connection));
        });
        var result = service().record(player, "zeus", "minecraft:gold_ingot", 64, true, now).join();
        assertTrue(result.recorded());
        assertFalse(result.safeToRestore());
        assertEquals(25, result.roll());
        assertTrue(result.success());
        assertEquals(3, result.favorLevel());
        verify(database, times(1)).transaction(any());
        verifyNoInteractions(picker, favorUpdate);
    }

    @Test void failedWritesOnlyAllowRestorationWhenRollbackIsConfirmed() {
        when(database.transaction(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("write failed")));
        when(database.read(any())).thenReturn(CompletableFuture.completedFuture(null));
        var rolledBack = service().record(player, "zeus", "minecraft:gold_ingot", 64, true, now).join();
        assertFalse(rolledBack.recorded());
        assertTrue(rolledBack.safeToRestore());
        when(database.read(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("lookup failed")));
        var uncertain = service().record(player, "zeus", "minecraft:gold_ingot", 64, true, now).join();
        assertFalse(uncertain.recorded());
        assertFalse(uncertain.safeToRestore());
        verifyNoInteractions(picker, favorUpdate);
    }
}
