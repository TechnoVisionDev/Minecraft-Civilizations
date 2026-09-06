package io.github.empireage.civilizations.listener.progression;

import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Disables vanilla villager and wandering-trader commerce on every interaction path. */
public final class VillagerTradingListener implements Listener {
    private static final long MESSAGE_COOLDOWN_MS = 1500L;
    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVillagerInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof AbstractVillager)) return;
        event.setCancelled(true);
        explain(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMerchantOpen(InventoryOpenEvent event) {
        if (!merchantInventory(event.getInventory().getType().name())) return;
        event.setCancelled(true);
        if (event.getPlayer() instanceof Player player) explain(player);
    }

    static boolean merchantInventory(String type) {
        return "MERCHANT".equals(type);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastMessage.remove(event.getPlayer().getUniqueId());
    }

    private void explain(Player player) {
        long now = System.currentTimeMillis();
        Long previous = lastMessage.put(player.getUniqueId(), now);
        if (previous == null || now - previous >= MESSAGE_COOLDOWN_MS) {
            player.sendMessage("Trading with villagers and wandering traders is disabled.");
        }
    }
}
