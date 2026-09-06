package io.github.empireage.civilizations.service.territory;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.domain.*;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SnapshotWarAccessTest {
    private final UUID attacker = UUID.randomUUID(), defender = UUID.randomUUID(), world = UUID.randomUUID();
    private final Instant start = Instant.parse("2026-09-05T21:00:00Z"), end = start.plusSeconds(14400);
    private final StateCache cache = mock(StateCache.class);
    private final Settings.War settings = mock(Settings.War.class);
    private final SnapshotWarAccess access = new SnapshotWarAccess(cache, settings);
    private final Claim enemy = claim(2, PlotType.PRIVATE);

    @BeforeEach
    void setup() {
        when(cache.ready()).thenReturn(true);
        when(settings.allowCapitalCombat()).thenReturn(true);
        when(settings.alwaysProtectedMaterials()).thenReturn(Set.of(Material.BEDROCK, Material.ENDER_CHEST));
        snapshot(WarState.ACTIVE);
    }

    private StateSnapshot snapshot(WarState state) {
        War war = new War(7, 1, 2, state, attacker, start.minusSeconds(86400), start, end,
            ZoneOffset.UTC, start, start, end.plusSeconds(604800), false, false, Set.of(attacker), Set.of(defender));
        StateSnapshot snapshot = new StateSnapshot(Map.of(), Map.of(),
            Map.of(attacker, new Member(1, attacker, "Attacker", Role.CITIZEN, start, start, 0, true, 0, true)),
            Map.of(), Map.of(enemy.key(), enemy), Map.of(), Map.of(), Map.of(), Map.of(7L, war),
            Map.of(1L, war, 2L, war), Map.of(), start);
        when(cache.snapshot()).thenReturn(snapshot);
        return snapshot;
    }

    @Test
    void fightingBreakingAndLootingUseExactWindowBoundaries() {
        for (var action : List.of(WarAccessPort.Action.PVP, WarAccessPort.Action.BREAK, WarAccessPort.Action.INTERACT)) {
            assertFalse(access.authorized(attacker, 1L, enemy, action, Material.CHEST, start.minusNanos(1)));
            assertTrue(access.authorized(attacker, 1L, enemy, action, Material.CHEST, start));
            assertTrue(access.authorized(attacker, 1L, enemy, action, Material.CHEST, end.minusNanos(1)));
            assertFalse(access.authorized(attacker, 1L, enemy, action, Material.CHEST, end));
        }
    }

    @Test
    void containerPolicyAllowsLootingInBothDirections() {
        ProtectionService protection = new ProtectionService(cache, new Settings.Protection(false, true, true, true),
            access, Clock.fixed(start, ZoneOffset.UTC));
        assertTrue(protection.authorize(attacker, enemy.key(), ProtectionService.Action.CONTAINER, Material.CHEST, false).wartime());
        assertTrue(protection.authorize(attacker, enemy.key(), ProtectionService.Action.CONTAINER, null, false).allowed());
        assertTrue(access.authorized(defender, 2L, claim(1, PlotType.CIVIC), WarAccessPort.Action.INTERACT, Material.BARREL, start));
    }

    @Test
    void outsidersOtherCivilizationsAndProtectedMaterialsRemainDenied() {
        assertFalse(access.authorized(UUID.randomUUID(), 1L, enemy, WarAccessPort.Action.INTERACT, Material.CHEST, start));
        assertFalse(access.authorized(attacker, 1L, claim(3, PlotType.CIVIC), WarAccessPort.Action.BREAK, Material.STONE, start));
        assertFalse(access.authorized(attacker, 1L, enemy, WarAccessPort.Action.BREAK, Material.BEDROCK, start));
        assertFalse(access.authorized(attacker, 1L, enemy, WarAccessPort.Action.INTERACT, Material.ENDER_CHEST, start));
        when(cache.ready()).thenReturn(false);
        assertFalse(access.authorized(attacker, 1L, enemy, WarAccessPort.Action.INTERACT, Material.CHEST, start));
    }

    @Test
    void peaceAndCompletionRevokeAccessEvenInsideOriginalWindow() {
        for (var state : List.of(WarState.CANCELLED, WarState.TRUCE, WarState.COMPLETED)) {
            snapshot(state);
            assertFalse(access.authorized(attacker, 1L, enemy, WarAccessPort.Action.INTERACT, Material.CHEST, start));
        }
    }

    private Claim claim(long civ, PlotType type) {
        return new Claim(civ, new ChunkKey(world, (int) civ, 0), "world", civ, type, defender,
            null, null, null, null, Map.of(), null, null, ClaimSource.EXPANSION, Map.of(), start, null, Set.of(), 0);
    }
}
