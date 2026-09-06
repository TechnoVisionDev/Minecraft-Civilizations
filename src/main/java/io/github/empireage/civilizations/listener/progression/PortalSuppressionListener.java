package io.github.empireage.civilizations.listener.progression;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Removes physical dimension portals in favor of technology-gated command travel. */
public final class PortalSuppressionListener implements Listener {
    private static final long MESSAGE_COOLDOWN_MS = 1500L;
    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        if (event.getReason() != PortalCreateEvent.CreateReason.FIRE
            && event.getReason() != PortalCreateEvent.CreateReason.NETHER_PAIR) return;
        event.setCancelled(true);
        if (event.getEntity() instanceof Player player) explain(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEndPortalActivation(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getItem() == null
            || event.getClickedBlock().getType() != Material.END_PORTAL_FRAME
            || event.getItem().getType() != Material.ENDER_EYE) return;
        event.setCancelled(true);
        explain(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortalTravel(PlayerPortalEvent event) {
        if (!physicalDimensionTravel(event.getCause())) return;
        event.setCancelled(true);
        event.setCanCreatePortal(false);
        explain(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGatewayTravel(PlayerTeleportEvent event) {
        if (event instanceof PlayerPortalEvent || event.getCause() != PlayerTeleportEvent.TeleportCause.END_GATEWAY) return;
        event.setCancelled(true);
        explain(event.getPlayer());
    }

    private static boolean physicalDimensionTravel(PlayerTeleportEvent.TeleportCause cause) {
        return cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
            || cause == PlayerTeleportEvent.TeleportCause.END_PORTAL
            || cause == PlayerTeleportEvent.TeleportCause.END_GATEWAY;
    }

    private void explain(Player player) {
        long now = System.currentTimeMillis();
        Long previous = lastMessage.put(player.getUniqueId(), now);
        if (previous == null || now - previous >= MESSAGE_COOLDOWN_MS) {
            player.sendMessage("Physical dimension portals are disabled. Use /civ teleport <overworld|nether|end>.");
        }
    }
}
