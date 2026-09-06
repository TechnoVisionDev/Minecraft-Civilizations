package io.github.empireage.civilizations.listener.protection;

import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.domain.*;
import io.github.empireage.civilizations.service.territory.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.block.*;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaimProtectionListenerTest {
    private final UUID worldId = UUID.randomUUID(), actor = UUID.randomUUID(), owner = UUID.randomUUID();
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);

    private ProtectionService policy(Map<String, Boolean> flags, boolean citizen, WarAccessPort war) {
        when(world.getUID()).thenReturn(worldId);
        when(player.getUniqueId()).thenReturn(actor);
        Claim claim = new Claim(1, new ChunkKey(worldId, 1, 0), "world", 1, PlotType.PRIVATE, owner,
            null, null, null, null, flags, null, null, ClaimSource.EXPANSION, Map.of(), Instant.EPOCH, owner, Set.of(), 0);
        Member member = new Member(1, actor, "Citizen", Role.CITIZEN, Instant.EPOCH, Instant.EPOCH, 0, true, 0, false);
        Member plotOwner = new Member(1, owner, "Owner", Role.CITIZEN, Instant.EPOCH, Instant.EPOCH, 0, true, 0, false);
        StateSnapshot snapshot = new StateSnapshot(Map.of(), Map.of(), citizen ? Map.of(actor, member, owner, plotOwner) : Map.of(owner, plotOwner),
            Map.of(), Map.of(claim.key(), claim), Map.of(1L, List.of(claim)), Map.of(), Map.of(), Map.of(),
            Map.of(), Map.of(), Instant.EPOCH);
        return new ProtectionService(() -> snapshot, () -> true, new Settings.Protection(false, true, true, true),
            war, Clock.systemUTC());
    }

    private Block block(int x, Material material) {
        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(new Location(world, x, 64, 0));
        when(block.getType()).thenReturn(material);
        when(block.getState()).thenReturn(material == Material.CHEST ? mock(Chest.class) : mock(BlockState.class));
        return block;
    }

    @Test void citizenContainerAccessDoesNotGrantDestruction() {
        assertContainerAccessDoesNotGrantDestruction("citizen_containers", true);
    }

    @Test void publicContainerAccessDoesNotGrantDestruction() {
        assertContainerAccessDoesNotGrantDestruction("public_containers", false);
    }

    private void assertContainerAccessDoesNotGrantDestruction(String flag, boolean citizen) {
        ProtectionService policy = policy(Map.of(flag, true), citizen, WarAccessPort.DENY_ALL);
        assertTrue(policy.authorize(actor, new ChunkKey(worldId, 1, 0), ProtectionService.Action.CONTAINER,
            Material.CHEST, false).allowed());
        BlockBreakEvent event = new BlockBreakEvent(block(16, Material.CHEST), player);
        new ClaimProtectionListener(policy).onBlockBreak(event);
        assertTrue(event.isCancelled());
    }

    @Test void ownerCanStillBreakContainers() {
        var policy = policy(Map.of(), true, WarAccessPort.DENY_ALL);
        when(player.getUniqueId()).thenReturn(owner);
        BlockBreakEvent event = new BlockBreakEvent(block(16, Material.CHEST), player);
        new ClaimProtectionListener(policy).onBlockBreak(event);
        assertFalse(event.isCancelled());
    }

    @Test void wartimeBreakPermissionAllowsContainerRaiding() {
        var policy = policy(Map.of(), false, (actor, civ, claim, action, material, now) -> true);
        var listener = new ClaimProtectionListener(policy);
        BlockBreakEvent chest = new BlockBreakEvent(block(16, Material.CHEST), player);
        BlockBreakEvent stone = new BlockBreakEvent(block(16, Material.STONE), player);
        listener.onBlockBreak(chest);
        listener.onBlockBreak(stone);
        assertFalse(chest.isCancelled());
        assertFalse(stone.isCancelled());
    }

    @Test void openContainerCannotBeLootedAfterWarPermissionExpires() {
        var active = new java.util.concurrent.atomic.AtomicBoolean(true);
        var listener = new ClaimProtectionListener(policy(Map.of(), false,
            (actor, civ, claim, action, material, now) -> active.get()));
        var inventory = mock(org.bukkit.inventory.Inventory.class);
        var view = mock(org.bukkit.inventory.InventoryView.class);
        when(inventory.getLocation()).thenReturn(new Location(world, 16, 64, 0));
        when(view.getTopInventory()).thenReturn(inventory);
        var click = mock(org.bukkit.event.inventory.InventoryClickEvent.class);
        when(click.getWhoClicked()).thenReturn(player);
        when(click.getView()).thenReturn(view);
        listener.onInventoryClick(click);
        verify(click, never()).setCancelled(true);
        active.set(false);
        listener.onInventoryClick(click);
        verify(click).setCancelled(true);
        var drag = mock(org.bukkit.event.inventory.InventoryDragEvent.class);
        when(drag.getWhoClicked()).thenReturn(player);
        when(drag.getView()).thenReturn(view);
        listener.onInventoryDrag(drag);
        verify(drag).setCancelled(true);
    }

    @Test void bedCrossingFromWildernessIntoPrivateClaimIsRejected() {
        var listener = new ClaimProtectionListener(policy(Map.of(), true, WarAccessPort.DENY_ALL));
        var event = multi(15, 16);
        listener.onBlockPlace(event);
        assertTrue(event.isCancelled());
    }

    @Test void bedWhollyInWildernessIsAllowed() {
        var listener = new ClaimProtectionListener(policy(Map.of(), true, WarAccessPort.DENY_ALL));
        var event = multi(14, 15);
        listener.onBlockPlace(event);
        assertFalse(event.isCancelled());
    }

    @Test void ownerCanPlaceAcrossTheirOwnBoundary() {
        var listener = new ClaimProtectionListener(policy(Map.of(), true, WarAccessPort.DENY_ALL));
        when(player.getUniqueId()).thenReturn(owner);
        var event = multi(15, 16);
        listener.onBlockPlace(event);
        assertFalse(event.isCancelled());
    }

    private BlockMultiPlaceEvent multi(int first, int second) {
        Block one = block(first, Material.RED_BED), two = block(second, Material.RED_BED);
        BlockState a = mock(BlockState.class), b = mock(BlockState.class);
        when(a.getBlock()).thenReturn(one);
        when(b.getBlock()).thenReturn(two);
        return new BlockMultiPlaceEvent(List.of(a, b), one, mock(ItemStack.class), player, true);
    }
}
