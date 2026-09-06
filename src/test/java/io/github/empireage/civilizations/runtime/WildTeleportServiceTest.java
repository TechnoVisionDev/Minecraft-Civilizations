package io.github.empireage.civilizations.runtime;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.Claim;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.persistence.PersistentDataContainer;
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
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WildTeleportServiceTest {
    @Test void samplesWithinAnOffsetBorderAndAtSafeSurfaceHeight() {
        try (Harness h = new Harness()) {
            h.centerX = 1200.25; h.centerZ = -3400.75; h.size = 101.5;
            assertTrue(h.service.teleport(h.player).success()); h.service.tick();
            assertNotNull(h.destination);
            assertSame(h.world, h.destination.getWorld());
            assertEquals(65, h.destination.getY());
            assertTrue(h.destination.getX() >= h.centerX - h.size / 2 + 1);
            assertTrue(h.destination.getX() <= h.centerX + h.size / 2 - 1);
            assertTrue(h.destination.getZ() >= h.centerZ - h.size / 2 + 1);
            assertTrue(h.destination.getZ() <= h.centerZ + h.size / 2 - 1);
            verify(h.player).setFallDistance(0);
        }
    }

    @Test void manualCooldownSurvivesServiceRestartAndExpiresAtExactlyTwelveHours() {
        try (Harness h = new Harness()) {
            assertTrue(h.service.teleport(h.player).success()); h.service.tick();
            assertEquals(h.clock.instant().plus(Duration.ofHours(12)).toEpochMilli(), h.data.get(h.cooldownKey()));
            verify(h.player).saveData();
            h.service.close(); h.service = h.newService();
            assertFalse(h.service.teleport(h.player).success());
            h.clock.now = h.clock.now.plus(Duration.ofHours(12)).minusMillis(1);
            assertFalse(h.service.teleport(h.player).success());
            h.clock.now = h.clock.now.plusMillis(1);
            assertTrue(h.service.teleport(h.player).success()); h.service.tick();
            verify(h.player, times(2)).teleport(any(Location.class), eq(TeleportCause.COMMAND));
        }
    }

    @Test void firstJoinIsAutomaticAndDoesNotUseTheManualCooldown() {
        try (Harness h = new Harness()) {
            when(h.player.hasPlayedBefore()).thenReturn(false);
            h.service.onJoin(h.join());
            h.service.tick(); assertNull(h.destination, "wait for normal join processing before moving the player");
            h.clock.now = h.clock.now.plusSeconds(1); h.service.tick();
            verify(h.player).teleport(any(Location.class), eq(TeleportCause.PLUGIN));
            assertFalse(h.data.containsKey(h.cooldownKey())); assertFalse(h.data.containsKey(h.initialKey()));
            assertTrue(h.service.teleport(h.player).success()); h.service.tick();
            assertTrue(h.data.containsKey(h.cooldownKey()));
            assertFalse(h.service.teleport(h.player).success());
        }
    }

    @Test void returningPlayersAreNotRandomlyMovedOnJoin() {
        try (Harness h = new Harness()) {
            h.service.onJoin(h.join()); h.clock.now = h.clock.now.plusSeconds(2); h.service.tick();
            verify(h.player, never()).teleport(any(Location.class), any(TeleportCause.class));
            assertTrue(h.data.isEmpty());
        }
    }

    @Test void cancelledManualTeleportDoesNotConsumeCooldown() {
        try (Harness h = new Harness()) {
            h.allowTeleport = false;
            assertTrue(h.service.teleport(h.player).success()); h.service.tick();
            assertFalse(h.data.containsKey(h.cooldownKey()));
            h.allowTeleport = true;
            assertTrue(h.service.teleport(h.player).success()); h.service.tick();
            assertTrue(h.data.containsKey(h.cooldownKey()));
        }
    }

    @Test void failedInitialPlacementRemainsFreeAfterReconnect() {
        try (Harness h = new Harness()) {
            when(h.player.hasPlayedBefore()).thenReturn(false); h.allowTeleport = false;
            h.service.onJoin(h.join()); h.clock.now = h.clock.now.plusSeconds(1); h.service.tick();
            assertTrue(h.data.containsKey(h.initialKey())); assertFalse(h.data.containsKey(h.cooldownKey()));
            h.service.close(); h.service = h.newService();
            when(h.player.hasPlayedBefore()).thenReturn(true); h.allowTeleport = true;
            h.service.onJoin(h.join()); h.clock.now = h.clock.now.plusSeconds(1); h.service.tick();
            assertFalse(h.data.containsKey(h.initialKey())); assertFalse(h.data.containsKey(h.cooldownKey()));
            assertTrue(h.service.teleport(h.player).success());
        }
    }

    @Test void firstJoinWaitsForTerritoryDataButManualTravelFailsClosedDuringWarmup() {
        try (Harness h = new Harness()) {
            when(h.cache.ready()).thenReturn(false);
            assertFalse(h.service.teleport(h.player).success());
            when(h.player.hasPlayedBefore()).thenReturn(false); h.service.onJoin(h.join());
            h.clock.now = h.clock.now.plusSeconds(1); h.service.tick(); assertNull(h.destination);
            when(h.cache.ready()).thenReturn(true); h.service.tick(); assertNotNull(h.destination);
            assertFalse(h.data.containsKey(h.cooldownKey()));
        }
    }

    @Test void claimedChunksAreNeverLoadedAsCandidateDestinationsAndSearchIsBounded() {
        try (Harness h = new Harness()) {
            when(h.snapshot.claim(any(ChunkKey.class))).thenReturn(mock(Claim.class));
            assertTrue(h.service.teleport(h.player).success());
            for (int i = 0; i < 100; i++) h.service.tick();
            verify(h.world, never()).getHighestBlockYAt(anyInt(), anyInt(), any(HeightMap.class));
            verify(h.snapshot, times(64)).claim(any(ChunkKey.class));
            assertNull(h.destination); assertFalse(h.data.containsKey(h.cooldownKey()));
            when(h.snapshot.claim(any(ChunkKey.class))).thenReturn(null);
            assertTrue(h.service.teleport(h.player).success()); h.service.tick(); assertNotNull(h.destination);
        }
    }

    @Test void newlyClaimedDestinationIsRecheckedAfterTerrainLoads() {
        try (Harness h = new Harness()) {
            when(h.snapshot.claim(any(ChunkKey.class))).thenReturn(null, mock(Claim.class));
            assertTrue(h.service.teleport(h.player).success()); h.service.tick();
            assertNull(h.destination); assertFalse(h.data.containsKey(h.cooldownKey()));
        }
    }

    @Test void waterHazardousFloorsAndObstructedHeadSpaceAreRejected() {
        try (Harness h = new Harness()) {
            when(h.floor.isLiquid()).thenReturn(true);
            assertNull(WildTeleportService.safeSurface(h.world, 0, 0, 0));
            when(h.floor.isLiquid()).thenReturn(false);
            for (Material hazard : new Material[]{Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.CACTUS, Material.POWDER_SNOW}) {
                when(h.floor.getType()).thenReturn(hazard);
                assertNull(WildTeleportService.safeSurface(h.world, 0, 0, 0));
            }
            when(h.floor.getType()).thenReturn(h.ground); when(h.head.isEmpty()).thenReturn(false);
            assertNull(WildTeleportService.safeSurface(h.world, 0, 0, 0));
            when(h.head.isEmpty()).thenReturn(true); when(h.feet.isEmpty()).thenReturn(false);
            assertNull(WildTeleportService.safeSurface(h.world, 0, 0, 0));
        }
    }

    @Test void voidAndBuildHeightLimitAreRejected() {
        try (Harness h = new Harness()) {
            when(h.world.getHighestBlockYAt(anyInt(), anyInt(), any(HeightMap.class))).thenReturn(-65);
            assertNull(WildTeleportService.safeSurface(h.world, 0, 0, 0));
            when(h.world.getHighestBlockYAt(anyInt(), anyInt(), any(HeightMap.class))).thenReturn(318);
            assertNull(WildTeleportService.safeSurface(h.world, 0, 0, 0));
        }
    }

    @Test void borderChangesDuringSearchAreRespected() {
        try (Harness h = new Harness()) {
            assertTrue(h.service.teleport(h.player).success());
            when(h.world.getHighestBlockYAt(anyInt(), anyInt(), any(HeightMap.class))).thenAnswer(call -> { h.size = 1; return 64; });
            h.service.tick(); assertNull(h.destination);
            assertFalse(h.data.containsKey(h.cooldownKey()));
        }
    }

    @Test void boundsRespectExtremeCoordinatesAndRejectImpossibleBorders() {
        try (Harness h = new Harness()) {
            h.size = 59_999_968; h.centerX = 29_999_984; h.centerZ = -29_999_984;
            var bounds = WildTeleportService.bounds(h.border);
            assertNotNull(bounds);
            assertTrue(bounds.maxX() + 0.5 < 29_999_984);
            assertTrue(bounds.minZ() + 0.5 > -29_999_984);
            h.size = 1; assertNull(WildTeleportService.bounds(h.border));
            h.size = Double.NaN; assertNull(WildTeleportService.bounds(h.border));
        }
    }

    @Test void duplicateRequestsQuitAndShutdownDoNotLeaveQueuedTeleports() {
        try (Harness h = new Harness()) {
            assertTrue(h.service.teleport(h.player).success()); assertFalse(h.service.teleport(h.player).success());
            PlayerQuitEvent quit = mock(PlayerQuitEvent.class); when(quit.getPlayer()).thenReturn(h.player);
            h.service.onQuit(quit); h.service.tick(); assertNull(h.destination);
            assertTrue(h.service.teleport(h.player).success()); h.service.close(); h.service.tick();
            assertNull(h.destination); assertFalse(h.data.containsKey(h.cooldownKey()));
            assertFalse(h.service.teleport(h.player).success());
        }
    }

    @Test void deadPlayersWrongDimensionAndMissingWorldCannotStartTravel() {
        try (Harness h = new Harness()) {
            when(h.player.isDead()).thenReturn(true); assertFalse(h.service.teleport(h.player).success());
            when(h.player.isDead()).thenReturn(false); when(h.world.getEnvironment()).thenReturn(World.Environment.NETHER);
            assertFalse(h.service.teleport(h.player).success());
            h.bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(null);
            assertFalse(h.service.teleport(h.player).success()); assertTrue(h.data.isEmpty());
        }
    }

    @Test void commandValidatesPermissionArgumentsAndPlayerSender() {
        try (Harness h = new Harness()) {
            Command command = mock(Command.class);
            when(h.player.hasPermission("civilizations.wild")).thenReturn(false);
            h.service.onCommand(h.player, command, "wild", new String[0]); h.service.tick(); assertNull(h.destination);
            when(h.player.hasPermission("civilizations.wild")).thenReturn(true);
            h.service.onCommand(h.player, command, "wild", new String[]{"other"}); h.service.tick(); assertNull(h.destination);
            CommandSender console = mock(CommandSender.class);
            h.service.onCommand(console, command, "wild", new String[0]); verify(console).sendMessage("Only players can use /wild.");
            h.service.onCommand(h.player, command, "wild", new String[0]); h.service.tick(); assertNotNull(h.destination);
        }
    }

    @Test void timedOutInitialPlacementRetainsItsFreeRetryAndReadableCooldown() {
        try (Harness h = new Harness()) {
            when(h.cache.ready()).thenReturn(false); when(h.player.hasPlayedBefore()).thenReturn(false);
            h.service.onJoin(h.join()); h.clock.now = h.clock.now.plus(Duration.ofMinutes(2)); h.service.tick();
            assertNull(h.destination); assertTrue(h.data.containsKey(h.initialKey()));
            when(h.cache.ready()).thenReturn(true); assertTrue(h.service.teleport(h.player).success());
            assertEquals("12h 0m", WildTeleportService.remaining(Duration.ofHours(12).toMillis()));
            assertEquals("1s", WildTeleportService.remaining(1));
        }
    }

    private static final class Harness implements AutoCloseable {
        private final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        private final JavaPlugin plugin = mock(JavaPlugin.class);
        private final StateCache cache = mock(StateCache.class);
        private final StateSnapshot snapshot = mock(StateSnapshot.class);
        private final World world = mock(World.class);
        private final WorldBorder border = mock(WorldBorder.class);
        private final Player player = mock(Player.class);
        private final Material ground = mock(Material.class);
        private final Block floor = mock(Block.class), feet = mock(Block.class), head = mock(Block.class);
        private final Map<NamespacedKey, Object> data = new HashMap<>();
        private final MutableClock clock = new MutableClock();
        private WildTeleportService service;
        private double centerX, centerZ, size = 1000;
        private boolean allowTeleport = true;
        private Location destination;

        private Harness() {
            when(plugin.getName()).thenReturn("Civilizations");
            when(plugin.getLogger()).thenReturn(Logger.getLogger("wild-test"));
            when(cache.ready()).thenReturn(true); when(cache.snapshot()).thenReturn(snapshot);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            when(world.getUID()).thenReturn(UUID.randomUUID()); when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
            when(world.getWorldBorder()).thenReturn(border); when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
            when(border.getCenter()).thenAnswer(call -> new Location(world, centerX, 0, centerZ));
            when(border.getSize()).thenAnswer(call -> size);
            when(border.isInside(any(Location.class))).thenAnswer(call -> {
                Location l = call.getArgument(0);
                return Math.abs(l.getX() - centerX) < size / 2 && Math.abs(l.getZ() - centerZ) < size / 2;
            });
            when(ground.isSolid()).thenReturn(true); when(floor.getType()).thenReturn(ground);
            when(feet.isEmpty()).thenReturn(true); when(head.isEmpty()).thenReturn(true);
            when(world.getHighestBlockYAt(anyInt(), anyInt(), any(HeightMap.class))).thenReturn(64);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> switch (call.<Integer>getArgument(1)) {
                case 64 -> floor; case 65 -> feet; default -> head;
            });
            when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.isOnline()).thenReturn(true);
            when(player.hasPlayedBefore()).thenReturn(true); when(player.getLocation()).thenAnswer(call -> new Location(world, 0, 65, 0));
            when(player.teleport(any(Location.class), any(TeleportCause.class))).thenAnswer(call -> {
                if (allowTeleport) destination = call.getArgument(0);
                return allowTeleport;
            });
            PersistentDataContainer pdc = mock(PersistentDataContainer.class);
            when(player.getPersistentDataContainer()).thenReturn(pdc);
            when(pdc.get(any(), any())).thenAnswer(call -> data.get(call.getArgument(0)));
            doAnswer(call -> { data.put(call.getArgument(0), call.getArgument(2)); return null; }).when(pdc).set(any(), any(), any());
            doAnswer(call -> { data.remove(call.getArgument(0)); return null; }).when(pdc).remove(any());
            service = newService();
        }
        private WildTeleportService newService() { return new WildTeleportService(plugin, cache, "world", clock, new Random(42)); }
        private NamespacedKey cooldownKey() { return new NamespacedKey(plugin, "wild-next-use"); }
        private NamespacedKey initialKey() { return new NamespacedKey(plugin, "wild-initial-pending"); }
        private PlayerJoinEvent join() { PlayerJoinEvent event = mock(PlayerJoinEvent.class); when(event.getPlayer()).thenReturn(player); return event; }
        @Override public void close() { service.close(); bukkit.close(); }
    }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-04T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
