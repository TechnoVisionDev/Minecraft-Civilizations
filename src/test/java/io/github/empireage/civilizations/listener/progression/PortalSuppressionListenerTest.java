package io.github.empireage.civilizations.listener.progression;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortalSuppressionListenerTest {
    private final PortalSuppressionListener listener = new PortalSuppressionListener();

    @Test
    void netherPortalCreationIsAlwaysCancelled() {
        PortalCreateEvent event = mock(PortalCreateEvent.class);
        when(event.getReason()).thenReturn(PortalCreateEvent.CreateReason.NETHER_PAIR);

        listener.onPortalCreate(event);

        verify(event).setCancelled(true);
    }

    @Test
    void physicalPortalTravelCannotGenerateOrTeleport() {
        Player player = player();
        PlayerPortalEvent event = mock(PlayerPortalEvent.class);
        when(event.getCause()).thenReturn(PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
        when(event.getPlayer()).thenReturn(player);

        listener.onPortalTravel(event);

        verify(event).setCancelled(true);
        verify(event).setCanCreatePortal(false);
    }

    @Test
    void endPortalFramesRejectEyes() {
        Player player = player();
        Block frame = mock(Block.class);
        when(frame.getType()).thenReturn(Material.END_PORTAL_FRAME);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getClickedBlock()).thenReturn(frame);
        when(event.getItem()).thenReturn(new ItemStack(Material.ENDER_EYE));
        when(event.getPlayer()).thenReturn(player);

        listener.onEndPortalActivation(event);

        verify(event).setCancelled(true);
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }
}
