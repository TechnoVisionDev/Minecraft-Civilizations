package io.github.empireage.civilizations.service.territory;

import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.domain.Claim;
import io.github.empireage.civilizations.domain.ClaimSource;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.PlotType;
import io.github.empireage.civilizations.domain.Role;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectionServiceTest {
    private static final UUID WORLD = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID LEADER = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID CITIZEN = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID OWNER = UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID TRUSTED = UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID OUTSIDER = UUID.fromString("20000000-0000-0000-0000-000000000005");

    private AtomicBoolean ready;
    private ProtectionService protection;

    @BeforeEach
    void setUp() {
        ready = new AtomicBoolean(true);
        StateSnapshot snapshot = snapshot();
        protection = new ProtectionService(() -> snapshot, ready::get,
            new Settings.Protection(false, true, true, true),
            WarAccessPort.DENY_ALL, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void warmupIsFailClosedEvenAtAnApparentlyWildernessCoordinate() {
        ready.set(false);
        assertFalse(protection.authorize(OUTSIDER, key(99, 99), ProtectionService.Action.BREAK,
            Material.STONE, false).allowed());
        assertFalse(protection.boundaryCompatible(key(99, 99), key(100, 99)));
        assertTrue(protection.claimed(key(99, 99)), "unknown land is treated as protected while warming");
    }

    @Test
    void publicPlotTypesApplyTheirDistinctPolicies() {
        assertTrue(protection.authorize(LEADER, key(0, 0), ProtectionService.Action.BREAK,
            Material.STONE, false).allowed());
        assertFalse(protection.authorize(CITIZEN, key(0, 0), ProtectionService.Action.BREAK,
            Material.STONE, false).allowed());
        assertTrue(protection.authorize(CITIZEN, key(1, 0), ProtectionService.Action.BREAK,
            Material.STONE, false).allowed());
        assertFalse(protection.authorize(OUTSIDER, key(1, 0), ProtectionService.Action.BREAK,
            Material.STONE, false).allowed());
        assertFalse(protection.authorize(CITIZEN, key(2, 0), ProtectionService.Action.INTERACT,
            Material.OAK_DOOR, false).allowed(), "civilization listings remain protected until purchase");
    }

    @Test
    void privateTenureOverridesLeadershipAndHonorsTrust() {
        assertTrue(protection.authorize(OWNER, key(3, 0), ProtectionService.Action.CONTAINER,
            Material.CHEST, false).allowed());
        assertTrue(protection.authorize(TRUSTED, key(3, 0), ProtectionService.Action.BREAK,
            Material.STONE, false).allowed());
        assertFalse(protection.authorize(LEADER, key(3, 0), ProtectionService.Action.BREAK,
            Material.STONE, false).allowed(), "leadership has no automatic private-plot bypass");
    }

    @Test
    void machineryCannotCrossDifferentAccessControllers() {
        assertTrue(protection.boundaryCompatible(key(0, 0), key(0, 1)),
            "two administrative public plots share an access controller");
        assertFalse(protection.boundaryCompatible(key(0, 0), key(1, 0)),
            "civic machinery cannot feed a common plot");
        assertFalse(protection.boundaryCompatible(key(1, 0), key(3, 0)),
            "public machinery cannot cross into a private plot");
        assertFalse(protection.boundaryCompatible(key(1, 0), key(10, 0)),
            "machinery cannot cross sovereignty");
    }

    @Test
    void explosionsCannotCrossAdjacentClaimsEvenWithTheSameController() {
        assertFalse(protection.sameClaimBoundary(key(0, 0), key(0, 1)),
            "each sovereign chunk is an explosion boundary during war");
        assertTrue(protection.sameClaimBoundary(key(0, 0), key(0, 0)));
    }

    @Test
    void configuredWartimeDropsAreExposedToTheListener() {
        ProtectionService dropping = new ProtectionService(ProtectionServiceTest::snapshot, () -> true,
            new Settings.Protection(false, true, true, true), WarAccessPort.DENY_ALL,
            Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC), true);
        assertTrue(dropping.attackerBlockDrops());
        assertFalse(protection.attackerBlockDrops());
    }

    @Test
    void dedicatedRedstoneFlagDoesNotAlsoOpenOrdinaryInteractions() {
        StateSnapshot state = snapshotWithRedstoneFlag();
        ProtectionService flagged = new ProtectionService(() -> state, () -> true,
            new Settings.Protection(false, true, true, true), WarAccessPort.DENY_ALL,
            Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC));
        assertTrue(flagged.authorize(OUTSIDER, key(4, 0), ProtectionService.Action.REDSTONE,
            Material.LEVER, false).allowed());
        assertFalse(flagged.authorize(OUTSIDER, key(4, 0), ProtectionService.Action.INTERACT,
            Material.OAK_DOOR, false).allowed());
    }

    @Test
    void friendlyFireIsDisabledOnlyInOwnClaimsByDefault() {
        assertFalse(protection.friendlyFire(LEADER, CITIZEN, key(0, 0), false).allowed());
        assertTrue(protection.friendlyFire(LEADER, CITIZEN, key(99, 99), false).allowed());
    }

    private static StateSnapshot snapshot() {
        Claim civic = claim(1, 1, 0, 0, PlotType.CIVIC, null, null, Set.of());
        Claim civicNeighbor = claim(2, 1, 0, 1, PlotType.CIVIC, null, null, Set.of());
        Claim common = claim(3, 1, 1, 0, PlotType.COMMON, null, null, Set.of());
        Claim forSale = claim(4, 1, 2, 0, PlotType.FOR_SALE, null, "CIVILIZATION", Set.of());
        Claim privatePlot = claim(5, 1, 3, 0, PlotType.PRIVATE, OWNER, null, Set.of(TRUSTED));
        Claim other = claim(6, 2, 10, 0, PlotType.CIVIC, null, null, Set.of());
        List<Claim> claims = List.of(civic, civicNeighbor, common, forSale, privatePlot, other);
        Map<ChunkKey, Claim> byChunk = new HashMap<>();
        claims.forEach(claim -> byChunk.put(claim.key(), claim));
        List<Member> members = List.of(member(LEADER, Role.LEADER), member(CITIZEN, Role.CITIZEN),
            member(OWNER, Role.CITIZEN), member(TRUSTED, Role.CITIZEN));
        Map<UUID, Member> byPlayer = new HashMap<>();
        members.forEach(member -> byPlayer.put(member.playerId(), member));
        return new StateSnapshot(Map.of(), Map.of(), byPlayer, Map.of(1L, members), byChunk,
            Map.of(1L, claims.subList(0, 5), 2L, List.of(other)), Map.of(), Map.of(), Map.of(),
            Map.of(), Map.of(), Instant.EPOCH);
    }

    private static StateSnapshot snapshotWithRedstoneFlag() {
        StateSnapshot base = snapshot();
        Claim flagged = new Claim(7, key(4, 0), "world", 1, PlotType.PRIVATE, OWNER, null, OWNER,
            null, null, Map.of("redstone", true), null, null, ClaimSource.EXPANSION, Map.of(),
            Instant.EPOCH, null, Set.of(), 0);
        Map<ChunkKey, Claim> claims = new HashMap<>(base.claims());
        claims.put(flagged.key(), flagged);
        Map<Long, List<Claim>> byCivilization = new HashMap<>(base.claimsByCivilization());
        List<Claim> owned = new java.util.ArrayList<>(byCivilization.get(1L));
        owned.add(flagged);
        byCivilization.put(1L, List.copyOf(owned));
        return new StateSnapshot(base.civilizations(), base.civilizationNames(), base.memberships(),
            base.membersByCivilization(), claims, byCivilization, base.technologies(), base.research(),
            base.wars(), base.warsByCivilization(), base.objectivesByWar(), base.loadedAt());
    }

    private static Member member(UUID player, Role role) {
        return new Member(1, player, "Player", role, Instant.EPOCH, Instant.EPOCH, 0, true, 0, false);
    }

    private static Claim claim(long id, long civilization, int x, int z, PlotType type, UUID owner,
                               String listingKind, Set<UUID> trust) {
        return new Claim(id, key(x, z), "world", civilization, type, owner, listingKind, owner,
            type == PlotType.FOR_SALE ? new java.math.BigDecimal("100.00") : null, null, Map.of(),
            null, null, ClaimSource.EXPANSION, Map.of(), Instant.EPOCH, null, trust, 0);
    }

    private static ChunkKey key(int x, int z) {
        return new ChunkKey(WORLD, x, z);
    }
}
