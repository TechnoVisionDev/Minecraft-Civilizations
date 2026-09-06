package io.github.empireage.civilizations.runtime;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.Claim;
import io.github.empireage.civilizations.service.progression.CapabilityPolicy;
import io.github.empireage.civilizations.service.progression.TechnologyAccess;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DimensionTeleportServiceTest {
    @Test void directEndCommandFindsIslandInsideOffsetBorderAfterWarmup() {
        try (Harness h = new Harness()) {
            Command command = mock(Command.class); when(command.getName()).thenReturn("end");
            h.service.onCommand(h.player, command, "end", new String[0]);
            h.service.tick(); assertNull(h.destination);
            h.advance(); h.service.tick();
            assertNotNull(h.destination); assertSame(h.world, h.destination.getWorld());
            assertEquals(65, h.destination.getY());
            assertTrue(Math.abs(h.destination.getX() - h.centerX) <= h.size / 2 - 1);
            assertTrue(Math.abs(h.destination.getZ() - h.centerZ) <= h.size / 2 - 1);
            verify(h.technology, atLeastOnce()).permitted(h.player, Set.of(CapabilityPolicy.TELEPORT_END));
            verify(h.player).setFallDistance(0);
            verify(h.player).setVelocity(any());
        }
    }

    @Test void netherShortcutFindsCavernBelowRoofAndAllowsTravelWithinSameDimension() {
        try (Harness h = new Harness()) {
            h.nether(); when(h.player.getWorld()).thenReturn(h.world);
            Command command = mock(Command.class); when(command.getName()).thenReturn("nether");
            h.service.onCommand(h.player, command, "nether", new String[0]);
            h.advance(); h.service.tick();
            assertEquals(65, h.destination.getY());
            verify(h.technology, atLeastOnce()).permitted(h.player, Set.of(CapabilityPolicy.TELEPORT_NETHER));
            assertTrue(h.service.teleport(h.player, "nether").message().contains("cooldown"));
            h.clock.now = h.clock.now.plusSeconds(60);
            assertTrue(h.service.teleport(h.player, "nether").success()); h.advance(); h.service.tick();
            verify(h.player, times(2)).teleport(any(Location.class), eq(TeleportCause.COMMAND));
        }
    }

    @Test void netherRejectsRoofEvenWhenItHasAPlayerBuiltFloorAboveIt() {
        try (Harness h = new Harness()) {
            h.nether(); h.floor = h.air;
            assertNull(DimensionTeleportService.randomLanding(h.world, 10, 10, 0));
            verify(h.world, never()).getBlockAt(anyInt(), intThat(y -> y >= 128), anyInt());
        }
    }

    @Test void endRejectsVoidPlatformsAndObstructedIslandSurfaces() {
        try (Harness h = new Harness()) {
            h.centerX = 0; h.centerZ = 0;
            assertNotNull(DimensionTeleportService.randomLanding(h.world, 10, 10, 0));
            for (Material material : new Material[]{Material.AIR, Material.OBSIDIAN, Material.CHORUS_PLANT}) {
                h.floor = h.block(material);
                assertNull(DimensionTeleportService.randomLanding(h.world, 10, 10, 0));
            }
            h.floor = h.block(Material.END_STONE);
            h.overrides.put(new Position(10, 66, 10), h.block(Material.END_STONE));
            assertNull(DimensionTeleportService.randomLanding(h.world, 10, 10, 0));
        }
    }

    @Test void endRequiresSupportedClearPatchAwayFromIslandEdge() {
        try (Harness h = new Harness()) {
            h.centerX = 0; h.centerZ = 0;
            assertNotNull(DimensionTeleportService.randomLanding(h.world, 10, 10, 0));
            h.overrides.put(new Position(11, 64, 10), h.air);
            assertNull(DimensionTeleportService.randomLanding(h.world, 10, 10, 0));
            h.overrides.clear(); h.overrides.put(new Position(9, 65, 10), h.block(Material.FIRE));
            assertNull(DimensionTeleportService.randomLanding(h.world, 10, 10, 0));
        }
    }

    @Test void netherRejectsLavaMagmaFireAndLowCeilings() {
        try (Harness h = new Harness()) {
            h.nether(); h.centerX = 0; h.centerZ = 0;
            for (Material hazard : new Material[]{Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE}) {
                h.floor = h.block(hazard);
                assertNull(DimensionTeleportService.randomLanding(h.world, 10, 10, 0));
            }
            h.floor = h.block(h.ground);
            h.overrides.put(new Position(11, 65, 10), h.block(Material.LAVA));
            assertNull(DimensionTeleportService.randomLanding(h.world, 10, 10, 0));
            h.overrides.clear(); h.overrides.put(new Position(10, 66, 10), h.block(h.ground));
            assertNull(DimensionTeleportService.randomLanding(h.world, 10, 10, 0));
        }
    }

    @Test void claimsAreSkippedBeforeTerrainLoadsAndCheckedAgainAfterLoading() {
        try (Harness h = new Harness()) {
            when(h.snapshot.claim(any(ChunkKey.class))).thenReturn(mock(Claim.class));
            assertTrue(h.service.teleport(h.player, "end").success()); h.advance(); h.service.tick();
            verify(h.world, never()).getHighestBlockYAt(anyInt(), anyInt(), any(HeightMap.class));
            when(h.snapshot.claim(any(ChunkKey.class))).thenReturn(null, mock(Claim.class));
            h.service.tick(); assertNull(h.destination);
            when(h.snapshot.claim(any(ChunkKey.class))).thenReturn(null);
            h.service.tick(); assertNotNull(h.destination);
        }
    }

    @Test void failedAndCancelledSearchesDoNotConsumeCooldown() {
        try (Harness h = new Harness()) {
            h.allowTeleport = false;
            assertTrue(h.service.teleport(h.player, "end").success()); h.advance(); h.service.tick();
            assertNull(h.destination);
            assertTrue(h.service.teleport(h.player, "end").success());
            h.allowTeleport = true; h.advance(); h.service.tick();
            assertNotNull(h.destination);
            assertFalse(h.service.teleport(h.player, "nether").success());
        }
    }

    @Test void searchIsBoundedAndCanBeRetriedWithoutCooldown() {
        try (Harness h = new Harness()) {
            h.floor = h.air;
            assertTrue(h.service.teleport(h.player, "end").success()); h.advance();
            for (int i = 0; i < 65; i++) h.service.tick();
            verify(h.world, times(64)).getHighestBlockYAt(anyInt(), anyInt(), any(HeightMap.class));
            assertNull(h.destination);
            assertTrue(h.service.teleport(h.player, "end").success());
        }
    }

    @Test void technologyIsRequiredAndRecheckedAfterWarmup() {
        try (Harness h = new Harness()) {
            when(h.technology.permitted(any(), anySet())).thenReturn(false);
            assertFalse(h.service.teleport(h.player, "end").success());
            when(h.technology.permitted(any(), anySet())).thenReturn(true);
            assertTrue(h.service.teleport(h.player, "end").success());
            when(h.technology.permitted(any(), anySet())).thenReturn(false);
            h.advance(); h.service.tick(); assertNull(h.destination);
            when(h.technology.permitted(any(), anySet())).thenReturn(true);
            assertTrue(h.service.teleport(h.player, "end").success());
        }
    }

    @Test void movementDamageAndDisconnectCancelPendingTravel() {
        try (Harness h = new Harness()) {
            assertTrue(h.service.teleport(h.player, "end").success());
            PlayerMoveEvent move = mock(PlayerMoveEvent.class); when(move.getPlayer()).thenReturn(h.player);
            when(move.getTo()).thenReturn(new Location(h.overworld, 2, 65, 0)); h.service.onMove(move);
            h.advance(); h.service.tick(); assertNull(h.destination);
            assertTrue(h.service.teleport(h.player, "end").success());
            EntityDamageEvent damage = mock(EntityDamageEvent.class); when(damage.getEntity()).thenReturn(h.player);
            h.service.onDamage(damage); h.advance(); h.service.tick(); assertNull(h.destination);
            assertTrue(h.service.teleport(h.player, "end").success());
            PlayerQuitEvent quit = mock(PlayerQuitEvent.class); when(quit.getPlayer()).thenReturn(h.player);
            h.service.onQuit(quit); h.advance(); h.service.tick(); assertNull(h.destination);
            assertTrue(h.service.teleport(h.player, "end").success());
        }
    }

    @Test void unavailableCacheTimeoutAndChangedBorderFailSafely() {
        try (Harness h = new Harness()) {
            when(h.cache.ready()).thenReturn(false);
            assertFalse(h.service.teleport(h.player, "end").success());
            when(h.cache.ready()).thenReturn(true);
            assertTrue(h.service.teleport(h.player, "end").success());
            when(h.cache.ready()).thenReturn(false); h.advance(); h.service.tick(); assertNull(h.destination);
            h.clock.now = h.clock.now.plusSeconds(120); h.service.tick();
            when(h.cache.ready()).thenReturn(true);
            assertTrue(h.service.teleport(h.player, "end").success()); h.size = 1;
            h.advance(); h.service.tick(); assertNull(h.destination);
            h.size = 100;
            assertTrue(h.service.teleport(h.player, "end").success());
        }
    }

    @Test void dimensionsAndSafeReturnRouteAreValidatedAndOverworldStillUsesAnchor() {
        try (Harness h = new Harness()) {
            h.bukkit.when(() -> Bukkit.getWorld("end")).thenReturn(null);
            assertFalse(h.service.teleport(h.player, "end").success());
            h.bukkit.when(() -> Bukkit.getWorld("end")).thenReturn(h.overworld);
            assertFalse(h.service.teleport(h.player, "end").success());
            h.bukkit.when(() -> Bukkit.getWorld("end")).thenReturn(h.world);
            h.bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(null);
            assertFalse(h.service.teleport(h.player, "end").success());
            h.bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(h.overworld);
            when(h.player.getWorld()).thenReturn(h.world);
            assertTrue(h.service.teleport(h.player, "overworld").success()); h.advance(); h.service.tick();
            assertSame(h.overworld, h.destination.getWorld()); assertEquals(0.5, h.destination.getX());
        }
    }

    @Test void directCommandChecksPermissionAndUsageAndCloseCancelsWork() {
        try (Harness h = new Harness()) {
            Command command = mock(Command.class); when(command.getName()).thenReturn("end");
            when(h.player.hasPermission("civilizations.use")).thenReturn(false);
            h.service.onCommand(h.player, command, "end", new String[0]); h.advance(); h.service.tick(); assertNull(h.destination);
            when(h.player.hasPermission("civilizations.use")).thenReturn(true);
            h.service.onCommand(h.player, command, "end", new String[]{"unexpected"}); h.advance(); h.service.tick(); assertNull(h.destination);
            assertTrue(h.service.teleport(h.player, "end").success());
            assertFalse(h.service.teleport(h.player, "end").success());
            h.service.close(); h.advance(); h.service.tick(); assertNull(h.destination);
            assertFalse(h.service.teleport(h.player, "end").success());
        }
    }

    @Test void borderIsRecheckedAfterTerrainLoads() {
        try (Harness h = new Harness()) {
            when(h.world.getHighestBlockYAt(anyInt(), anyInt(), any(HeightMap.class))).thenAnswer(call -> {
                h.size = 1; return 64;
            });
            assertTrue(h.service.teleport(h.player, "end").success()); h.advance(); h.service.tick();
            assertNull(h.destination);
        }
    }

    @Test void queueSharesSearchWorkAcrossPlayersAndTeleportCannotReenter() {
        try (Harness h = new Harness()) {
            Player second = mock(Player.class);
            when(second.getUniqueId()).thenReturn(UUID.randomUUID()); when(second.isOnline()).thenReturn(true);
            when(second.getLocation()).thenAnswer(call -> new Location(h.overworld, 0, 65, 0));
            when(second.teleport(any(Location.class), eq(TeleportCause.COMMAND))).thenReturn(true);
            assertTrue(h.service.teleport(h.player, "end").success());
            assertTrue(h.service.teleport(second, "end").success()); h.advance();
            when(h.player.teleport(any(Location.class), eq(TeleportCause.COMMAND))).thenAnswer(call -> {
                assertFalse(h.service.teleport(h.player, "end").success());
                PlayerMoveEvent move = mock(PlayerMoveEvent.class); when(move.getPlayer()).thenReturn(h.player);
                when(move.getTo()).thenReturn(call.getArgument(0)); h.service.onMove(move);
                return true;
            });
            h.service.tick();
            verify(second, never()).teleport(any(Location.class), eq(TeleportCause.COMMAND));
            verify(h.world, times(1)).getHighestBlockYAt(anyInt(), anyInt(), any(HeightMap.class));
            h.service.tick();
            verify(second).teleport(any(Location.class), eq(TeleportCause.COMMAND));
            verify(h.player, never()).sendMessage("Dimension teleport cancelled because you moved.");
        }
    }

    private record Position(int x, int y, int z) {}
    private static final class Harness implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final TechnologyAccess technology = mock(TechnologyAccess.class);
        final StateCache cache = mock(StateCache.class);
        final StateSnapshot snapshot = mock(StateSnapshot.class);
        final World world = mock(World.class), overworld = mock(World.class);
        final WorldBorder border = mock(WorldBorder.class), returnBorder = mock(WorldBorder.class);
        final Player player = mock(Player.class);
        final Material ground = mock(Material.class), empty = mock(Material.class);
        final Block air = block(empty), roof = block(Material.BEDROCK);
        final Map<Position, Block> overrides = new HashMap<>();
        final MutableClock clock = new MutableClock();
        final DimensionTeleportService service;
        Block floor = block(Material.END_STONE);
        double centerX = 1200.25, centerZ = -3400.75, size = 1000;
        boolean allowTeleport = true;
        Location destination;

        Harness() {
            when(plugin.getLogger()).thenReturn(Logger.getLogger("dimension-test"));
            when(cache.ready()).thenReturn(true); when(cache.snapshot()).thenReturn(snapshot);
            when(technology.permitted(any(), anySet())).thenReturn(true);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(overworld);
            bukkit.when(() -> Bukkit.getWorld("end")).thenReturn(world);
            bukkit.when(() -> Bukkit.getWorld("nether")).thenReturn(world);
            when(world.getUID()).thenReturn(UUID.randomUUID()); when(overworld.getUID()).thenReturn(UUID.randomUUID());
            when(world.getEnvironment()).thenReturn(World.Environment.THE_END);
            when(overworld.getEnvironment()).thenReturn(World.Environment.NORMAL);
            when(world.getWorldBorder()).thenReturn(border); when(world.getMaxHeight()).thenReturn(256);
            when(world.getLogicalHeight()).thenReturn(256);
            when(border.getCenter()).thenAnswer(call -> new Location(world, centerX, 0, centerZ));
            when(border.getSize()).thenAnswer(call -> size);
            when(border.isInside(any(Location.class))).thenAnswer(call -> {
                Location l = call.getArgument(0);
                return Math.abs(l.getX() - centerX) < size / 2 && Math.abs(l.getZ() - centerZ) < size / 2;
            });
            when(ground.isSolid()).thenReturn(true); when(ground.isOccluding()).thenReturn(true);
            when(air.isEmpty()).thenReturn(true); when(air.isPassable()).thenReturn(true);
            when(world.getHighestBlockYAt(anyInt(), anyInt(), any(HeightMap.class))).thenReturn(64);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
                int x = call.getArgument(0), y = call.getArgument(1), z = call.getArgument(2);
                return overrides.getOrDefault(new Position(x, y, z), y == 64 ? floor : y == 127 ? roof : y == 130 ? block(ground) : air);
            });
            when(overworld.getWorldBorder()).thenReturn(returnBorder); when(overworld.getMaxHeight()).thenReturn(320);
            when(returnBorder.isInside(any(Location.class))).thenReturn(true);
            when(overworld.getSpawnLocation()).thenReturn(new Location(overworld, 0, 65, 0));
            Block returnFloor = block(ground);
            when(overworld.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> call.<Integer>getArgument(1) == 64 ? returnFloor : air);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.isOnline()).thenReturn(true);
            when(player.hasPermission("civilizations.use")).thenReturn(true);
            when(player.getWorld()).thenReturn(overworld);
            when(player.getLocation()).thenAnswer(call -> new Location(overworld, 0, 65, 0));
            when(player.teleport(any(Location.class), eq(TeleportCause.COMMAND))).thenAnswer(call -> {
                if (allowTeleport) destination = call.getArgument(0);
                return allowTeleport;
            });
            Settings.Travel settings = new Settings.Travel(Duration.ofSeconds(5), Duration.ofSeconds(60), 0, 1,
                destination("world"), destination("nether"), destination("end"));
            service = new DimensionTeleportService(plugin, technology, cache, settings, clock, new Random(42));
        }
        void nether() { when(world.getEnvironment()).thenReturn(World.Environment.NETHER); when(world.getLogicalHeight()).thenReturn(128); floor = block(ground); }
        void advance() { clock.now = clock.now.plusSeconds(5); }
        Block block(Material material) { Block block = mock(Block.class); when(block.getType()).thenReturn(material); return block; }
        Settings.Destination destination(String name) { return new Settings.Destination(name, null, null, null, 0, 0); }
        @Override public void close() { service.close(); bukkit.close(); }
    }
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-04T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
