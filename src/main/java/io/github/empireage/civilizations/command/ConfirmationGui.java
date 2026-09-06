package io.github.empireage.civilizations.command;

import io.github.empireage.civilizations.config.Messages;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Single-use player confirmation menus for destructive or costly commands. */
public final class ConfirmationGui implements Listener {
    private static final int CONFIRM_SLOT = 11;
    private static final int DETAILS_SLOT = 13;
    private static final int DENY_SLOT = 15;
    private static final String PREFIX = "&8[&6Civilizations&8] &r";

    private final Clock clock;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ConfirmationGui() {
        this(Clock.systemUTC());
    }

    ConfirmationGui(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void open(Player player, String title, List<String> details, Instant expiresAt,
                     Runnable onConfirm, Runnable onDeny) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(expiresAt, "expiresAt");
        UUID nonce = UUID.randomUUID();
        ConfirmationHolder holder = new ConfirmationHolder(player.getUniqueId(), nonce);
        Inventory inventory = Bukkit.createInventory(holder, 27, trimTitle(title));
        holder.inventory = inventory;

        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
        inventory.setItem(CONFIRM_SLOT, item(Material.LIME_CONCRETE, "&a&lCONFIRM",
            List.of("&7Click to continue.")));
        inventory.setItem(DETAILS_SLOT, item(Material.PAPER, "&eReview this action", details));
        inventory.setItem(DENY_SLOT, item(Material.RED_CONCRETE, "&c&lDENY",
            List.of("&7Click to cancel.")));

        pending.put(player.getUniqueId(), new Pending(nonce, expiresAt,
            Objects.requireNonNull(onConfirm, "onConfirm"), Objects.requireNonNull(onDeny, "onDeny")));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ConfirmationHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.playerId.equals(player.getUniqueId())) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (event.getRawSlot() != CONFIRM_SLOT && event.getRawSlot() != DENY_SLOT) return;

        Pending value = pending.get(player.getUniqueId());
        if (value == null || !value.nonce.equals(holder.nonce)
            || !pending.remove(player.getUniqueId(), value)) return;
        player.closeInventory();
        if (!value.expiresAt.isAfter(clock.instant())) {
            value.onDeny.run();
            player.sendMessage(Messages.color(PREFIX + "&cThat confirmation expired. Run the command again."));
            return;
        }
        if (event.getRawSlot() == CONFIRM_SLOT) value.onConfirm.run();
        else {
            value.onDeny.run();
            player.sendMessage(Messages.color(PREFIX + "&7Action cancelled."));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ConfirmationHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ConfirmationHolder holder)) return;
        Pending value = pending.get(holder.playerId);
        if (value != null && value.nonce.equals(holder.nonce) && pending.remove(holder.playerId, value)) {
            value.onDeny.run();
        }
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Messages.color(name));
        List<String> rendered = new ArrayList<>();
        for (String line : lore) rendered.add(Messages.color(line));
        meta.setLore(rendered);
        item.setItemMeta(meta);
        return item;
    }

    private static String trimTitle(String value) {
        String colored = ChatColor.stripColor(Messages.color(value == null ? "Confirm action" : value));
        return colored.length() <= 32 ? colored : colored.substring(0, 32);
    }

    private static final class ConfirmationHolder implements InventoryHolder {
        private final UUID playerId;
        private final UUID nonce;
        private Inventory inventory;

        private ConfirmationHolder(UUID playerId, UUID nonce) {
            this.playerId = playerId;
            this.nonce = nonce;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record Pending(UUID nonce, Instant expiresAt, Runnable onConfirm, Runnable onDeny) {}
}
