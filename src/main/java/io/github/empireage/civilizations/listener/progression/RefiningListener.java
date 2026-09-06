package io.github.empireage.civilizations.listener.progression;

import io.github.empireage.civilizations.service.progression.CivicItemService;
import io.github.empireage.civilizations.service.progression.RefiningService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

public final class RefiningListener implements Listener {
    private final RefiningService refining;
    private final CivicItemService items;

    public RefiningListener(RefiningService refining, CivicItemService items) {
        this.refining = Objects.requireNonNull(refining, "refining");
        this.items = Objects.requireNonNull(items, "items");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!refining.isRefinery(top) || !(event.getWhoClicked() instanceof Player player)) return;
        int raw = event.getRawSlot();
        if (raw == RefiningService.COMPRESS_SLOT || raw == RefiningService.DECOMPRESS_SLOT) {
            event.setCancelled(true);
            RefiningService.Conversion conversion = refining.execute(top, raw == RefiningService.COMPRESS_SLOT);
            if (conversion.success()) refining.give(player, conversion.output());
            player.sendMessage(conversion.message());
            return;
        }
        if (raw == RefiningService.INPUT_SLOT) {
            event.setCancelled(true);
            handleInputClick(event, top);
            return;
        }
        if (raw >= 0 && raw < top.getSize()) {
            event.setCancelled(true);
            return;
        }
        if (event.isShiftClick() && items.identify(event.getCurrentItem()).isPresent()) {
            event.setCancelled(true);
            moveToInput(event, top);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!refining.isRefinery(event.getView().getTopInventory())) return;
        boolean touchesTop = event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize());
        if (!touchesTop) return;
        if (event.getRawSlots().stream().anyMatch(slot -> slot != RefiningService.INPUT_SLOT)
            || items.identify(event.getOldCursor()).isEmpty()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        if (!refining.isRefinery(inventory) || !(event.getPlayer() instanceof Player player)) return;
        ItemStack input = inventory.getItem(RefiningService.INPUT_SLOT);
        if (input != null && !input.getType().isAir()) {
            inventory.setItem(RefiningService.INPUT_SLOT, null);
            refining.give(player, input);
        }
    }

    private void handleInputClick(InventoryClickEvent event, Inventory inventory) {
        ItemStack cursor = event.getCursor();
        ItemStack stored = inventory.getItem(RefiningService.INPUT_SLOT);
        if (cursor == null || cursor.getType().isAir()) {
            if (stored != null) {
                event.setCursor(stored);
                inventory.setItem(RefiningService.INPUT_SLOT, null);
            }
            return;
        }
        if (items.identify(cursor).isEmpty()) return;
        if (stored == null || stored.getType().isAir()) {
            inventory.setItem(RefiningService.INPUT_SLOT, cursor.clone());
            event.setCursor(new ItemStack(Material.AIR));
            return;
        }
        if (!stored.isSimilar(cursor)) return;
        int moved = Math.min(cursor.getAmount(), stored.getMaxStackSize() - stored.getAmount());
        if (moved <= 0) return;
        stored.setAmount(stored.getAmount() + moved);
        cursor.setAmount(cursor.getAmount() - moved);
        inventory.setItem(RefiningService.INPUT_SLOT, stored);
        event.setCursor(cursor.getAmount() == 0 ? new ItemStack(Material.AIR) : cursor);
    }

    private void moveToInput(InventoryClickEvent event, Inventory inventory) {
        ItemStack source = event.getCurrentItem();
        ItemStack stored = inventory.getItem(RefiningService.INPUT_SLOT);
        if (stored != null && (!stored.isSimilar(source) || stored.getAmount() >= stored.getMaxStackSize())) return;
        int room = stored == null ? source.getMaxStackSize() : stored.getMaxStackSize() - stored.getAmount();
        int moved = Math.min(room, source.getAmount());
        if (stored == null) {
            stored = source.clone();
            stored.setAmount(moved);
        } else stored.setAmount(stored.getAmount() + moved);
        source.setAmount(source.getAmount() - moved);
        inventory.setItem(RefiningService.INPUT_SLOT, stored);
        event.setCurrentItem(source.getAmount() == 0 ? null : source);
    }
}
