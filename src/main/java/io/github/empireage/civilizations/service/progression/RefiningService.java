package io.github.empireage.civilizations.service.progression;

import io.github.empireage.civilizations.domain.ResourceKey;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.Objects;

/** The inventory-backed compression/decompression alternative to crafting recipes. */
public final class RefiningService {
    public static final int INPUT_SLOT = 11;
    public static final int COMPRESS_SLOT = 13;
    public static final int DECOMPRESS_SLOT = 15;

    private final CivicItemService items;

    public RefiningService(CivicItemService items) {
        this.items = Objects.requireNonNull(items, "items");
    }

    public Inventory open(Player player) {
        RefiningHolder holder = new RefiningHolder();
        Inventory inventory = Bukkit.createInventory(holder, 27, "Civic Material Refinery");
        holder.inventory = inventory;
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
        inventory.setItem(INPUT_SLOT, null);
        inventory.setItem(COMPRESS_SLOT, named(Material.ANVIL, "&aCompress one tier"));
        inventory.setItem(DECOMPRESS_SLOT, named(Material.CHEST, "&eDecompress one tier"));
        player.openInventory(inventory);
        return inventory;
    }

    public Conversion conversion(ItemStack input, boolean compress) {
        ResourceKey source = items.identify(input).orElse(null);
        if (source == null) return Conversion.denied("Place one authentic civic-material stack in the input slot.");
        int ratio = items.catalog().higherTierRatio();
        if (compress) {
            if (source.tier() >= 3) return Conversion.denied("Tier 3 civic materials cannot be compressed further.");
            if (input.getAmount() < ratio) return Conversion.denied("Compression requires " + ratio + " identical items.");
            return new Conversion(true, ratio, items.create(new ResourceKey(source.family(), source.tier() + 1), 1), "Compressed.");
        }
        if (source.tier() <= 1) return Conversion.denied("Tier 1 civic materials cannot be decompressed.");
        return new Conversion(true, 1, items.create(new ResourceKey(source.family(), source.tier() - 1), ratio), "Decompressed.");
    }

    public Conversion execute(Inventory inventory, boolean compress) {
        if (!(inventory.getHolder() instanceof RefiningHolder)) return Conversion.denied("That is not a civic refinery.");
        ItemStack input = inventory.getItem(INPUT_SLOT);
        Conversion conversion = conversion(input, compress);
        if (!conversion.success()) return conversion;
        int remaining = input.getAmount() - conversion.consumed();
        if (remaining == 0) inventory.setItem(INPUT_SLOT, null);
        else {
            input.setAmount(remaining);
            inventory.setItem(INPUT_SLOT, input);
        }
        return conversion;
    }

    public void give(Player player, ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        overflow.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
    }

    public boolean isRefinery(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof RefiningHolder;
    }

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        item.setItemMeta(meta);
        return item;
    }

    public record Conversion(boolean success, int consumed, ItemStack output, String message) {
        public static Conversion denied(String message) {
            return new Conversion(false, 0, null, message);
        }
    }

    public static final class RefiningHolder implements InventoryHolder {
        private Inventory inventory;

        private RefiningHolder() {}

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
