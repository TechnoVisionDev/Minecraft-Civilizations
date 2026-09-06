package io.github.empireage.civilizations.service.war;

import io.github.empireage.civilizations.config.Messages;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class WarItems {
    private final JavaPlugin plugin;
    private final NamespacedKey itemTypeKey;

    public WarItems(JavaPlugin plugin) {
        this.plugin = plugin;
        this.itemTypeKey = new NamespacedKey(plugin, "war_item");
    }

    public ItemStack charter() {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Messages.color("&6War Charter"));
        meta.setLore(List.of(Messages.color("&7Consumed when a valid campaign is declared."),
            Messages.color("&8Civilizations war item")));
        meta.getPersistentDataContainer().set(itemTypeKey, PersistentDataType.STRING, "charter");
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isCharter(ItemStack item) {
        return hasType(item, "charter");
    }

    public boolean isStandard(ItemStack item) {
        return hasType(item, "standard");
    }

    public boolean isStandard(Block block) {
        if (!isBanner(block.getType()) || !(block.getState() instanceof TileState state)) return false;
        return "standard".equals(state.getPersistentDataContainer().get(itemTypeKey, PersistentDataType.STRING));
    }

    public void registerRecipes() {
        NamespacedKey charterKey = new NamespacedKey(plugin, "war_charter");
        plugin.getServer().removeRecipe(charterKey);
        ShapedRecipe charterRecipe = new ShapedRecipe(charterKey, charter());
        charterRecipe.shape("PIP", "IBI", "PIP");
        charterRecipe.setIngredient('P', Material.PAPER);
        charterRecipe.setIngredient('I', Material.IRON_INGOT);
        charterRecipe.setIngredient('B', Material.WRITABLE_BOOK);
        plugin.getServer().addRecipe(charterRecipe);

        NamespacedKey standardKey = new NamespacedKey(plugin, "war_standard");
        plugin.getServer().removeRecipe(standardKey);
    }

    public void unregisterRecipes() {
        plugin.getServer().removeRecipe(new NamespacedKey(plugin, "war_charter"));
        plugin.getServer().removeRecipe(new NamespacedKey(plugin, "war_standard"));
    }

    private boolean hasType(ItemStack item, String expected) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        String value = item.getItemMeta().getPersistentDataContainer().get(itemTypeKey, PersistentDataType.STRING);
        return expected.equals(value);
    }

    private static boolean isBanner(Material material) {
        return material == Material.WHITE_BANNER || material == Material.WHITE_WALL_BANNER;
    }
}
