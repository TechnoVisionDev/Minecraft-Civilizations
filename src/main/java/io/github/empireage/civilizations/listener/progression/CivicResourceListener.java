package io.github.empireage.civilizations.listener.progression;

import io.github.empireage.civilizations.domain.ResourceKey;
import io.github.empireage.civilizations.service.progression.CivicItemService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/** Rejects spoofed compression inputs and prevents civic tokens becoming ordinary blocks. */
public final class CivicResourceListener implements Listener {
    private final CivicItemService items;

    public CivicResourceListener(CivicItemService items) {
        this.items = Objects.requireNonNull(items, "items");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        items.normalizeNames(event.getPlayer().getInventory());
        items.normalizeNames(event.getPlayer().getEnderChest());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        items.normalizeNames(event.getInventory());
        items.normalizeNames(event.getPlayer().getInventory());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        if (items.normalizeName(item)) event.getItem().setItemStack(item);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        ResourceKey resultKey = items.identify(result).orElse(null);
        if (resultKey == null) return;
        if (resultKey.tier() > 1 && !validCompressionMatrix(event.getInventory(), resultKey)) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        ResourceKey resultKey = items.identify(event.getRecipe().getResult()).orElse(null);
        if (resultKey != null && resultKey.tier() > 1
            && !validCompressionMatrix(event.getInventory(), resultKey)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!items.isCivicCandidate(event.getItemInHand())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage("Civic materials cannot be placed. Deposit or refine them instead.");
    }

    private boolean validCompressionMatrix(CraftingInventory inventory, ResourceKey result) {
        ResourceKey expected = new ResourceKey(result.family(), result.tier() - 1);
        int total = 0;
        for (ItemStack ingredient : inventory.getMatrix()) {
            if (ingredient == null || ingredient.getType().isAir()) continue;
            ResourceKey actual = items.identify(ingredient).orElse(null);
            if (!expected.equals(actual)) return false;
            total += ingredient.getAmount();
        }
        return total >= items.catalog().higherTierRatio();
    }
}
