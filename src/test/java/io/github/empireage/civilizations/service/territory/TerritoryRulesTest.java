package io.github.empireage.civilizations.service.territory;

import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.config.TechnologyCatalog;
import io.github.empireage.civilizations.domain.Claim;
import io.github.empireage.civilizations.domain.ClaimSource;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.Civilization;
import io.github.empireage.civilizations.domain.CivilizationStatus;
import io.github.empireage.civilizations.domain.HomeLocation;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.PlotType;
import io.github.empireage.civilizations.domain.Role;
import io.github.empireage.civilizations.domain.TechnologyDefinition;
import io.github.empireage.civilizations.util.Connectivity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerritoryRulesTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void connectivityUsesCardinalEdgesAndRejectsBridgeRemoval() {
        ChunkKey capital = key(0, 0);
        Set<ChunkKey> line = Set.of(capital, key(1, 0), key(2, 0));

        assertTrue(Connectivity.allConnected(line, capital));
        assertFalse(Connectivity.remainsConnectedAfterRemoval(line, capital, key(1, 0)));
        assertTrue(Connectivity.remainsConnectedAfterRemoval(line, capital, key(2, 0)));
        assertFalse(Connectivity.allConnected(Set.of(capital, key(1, 1)), capital),
            "diagonal contact must never count as connected territory");
    }

    @Test
    void conquestRequiresDefenderConnectivityAndWinnerAdjacency() {
        Claim defenderCapital = claim(1, 10, 0, 0, PlotType.CAPITAL);
        Claim bridge = claim(2, 10, 1, 0, PlotType.CIVIC);
        Claim defenderEdge = claim(3, 10, 2, 0, PlotType.PRIVATE);
        Claim winnerCapital = claim(4, 20, 2, 1, PlotType.CAPITAL);
        StateSnapshot snapshot = snapshot(List.of(defenderCapital, bridge, defenderEdge, winnerCapital), List.of(), Map.of());

        assertTrue(TerritoryRules.conquestTransferKeepsBothTerritoriesValid(snapshot, defenderEdge, 20));
        assertFalse(TerritoryRules.conquestTransferKeepsBothTerritoriesValid(snapshot, bridge, 20),
            "capturing a bridge would strand the defender edge");
        assertFalse(TerritoryRules.conquestTransferKeepsBothTerritoriesValid(snapshot, defenderCapital, 20));
    }

    @Test
    void establishedMembersAndTechnologyModifiersDriveCaps() {
        Member leader = member(10, Role.LEADER, true, 1);
        Member established = member(10, Role.CITIZEN, true, 2);
        Member newRecruit = member(10, Role.CITIZEN, false, 3);
        TechnologyCatalog technologies = new TechnologyCatalog(Map.of(
            "administration_ii", technology("administration_ii", Map.of("claim-capacity", 8, "plot-capacity", 1)),
            "imperial_administration", technology("imperial_administration", Map.of("claim-capacity", 32, "plot-capacity", 3))
        ));
        Settings.Claims claims = new Settings.Claims(8, 2, 128, 50, Duration.ofDays(7), Map.of());
        Settings.Plots plots = new Settings.Plots(4, new BigDecimal("100.00"), 10,
            Duration.ofSeconds(30), true, Duration.ofSeconds(5), Duration.ofSeconds(60));

        assertEquals(55, TerritoryRules.claimCapacity(List.of(leader, established, newRecruit),
            Set.of("administration_ii", "imperial_administration"), 5, claims, technologies));
        assertEquals(8, TerritoryRules.plotCapacity(Set.of("administration_ii", "imperial_administration"),
            plots, technologies));
    }

    private static TechnologyDefinition technology(String key, Map<String, Integer> modifiers) {
        return new TechnologyDefinition(key, key, "TEST", List.of(), 0, Duration.ZERO,
            Map.of(), Set.of(), modifiers);
    }

    private static Member member(long civilization, Role role, boolean established, long playerSuffix) {
        return new Member(civilization, new UUID(0, playerSuffix), "Player", role, Instant.EPOCH,
            Instant.EPOCH, 0, established, 0, false);
    }

    private static Claim claim(long id, long civilization, int x, int z, PlotType type) {
        return new Claim(id, key(x, z), "world", civilization, type, null, null, null,
            null, null, Map.of(), null, null, ClaimSource.EXPANSION, Map.of(), Instant.EPOCH,
            null, Set.of(), 0);
    }

    private static ChunkKey key(int x, int z) {
        return new ChunkKey(WORLD, x, z);
    }

    private static StateSnapshot snapshot(List<Claim> claims, List<Member> members,
                                          Map<Long, Set<String>> technologies) {
        Map<Long, Civilization> civilizations = new HashMap<>();
        civilizations.put(10L, civilization(10, key(0, 0)));
        civilizations.put(20L, civilization(20, key(2, 1)));
        Map<ChunkKey, Claim> byChunk = new HashMap<>();
        Map<Long, List<Claim>> byCivilization = new HashMap<>();
        for (Claim claim : claims) byChunk.put(claim.key(), claim);
        byCivilization.put(10L, claims.stream().filter(claim -> claim.civilizationId() == 10).toList());
        byCivilization.put(20L, claims.stream().filter(claim -> claim.civilizationId() == 20).toList());
        Map<UUID, Member> memberships = new HashMap<>();
        members.forEach(member -> memberships.put(member.playerId(), member));
        return new StateSnapshot(civilizations, Map.of(), memberships, Map.of(10L, members), byChunk,
            byCivilization, technologies, Map.of(), Map.of(), Map.of(), Map.of(), Instant.EPOCH);
    }

    private static Civilization civilization(long id, ChunkKey capital) {
        UUID leader = new UUID(0, id);
        return new Civilization(id, "Civ" + id, leader, CivilizationStatus.ACTIVE,
            capital, "world", new HomeLocation(WORLD, 0, 64, 0, 0, 0), BigDecimal.ZERO,
            BigDecimal.ZERO, 0, null, null, 0, null, Instant.EPOCH, 0);
    }
}
