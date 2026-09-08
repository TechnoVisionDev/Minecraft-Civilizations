package io.github.empireage.civilizations.runtime;

import io.github.empireage.civilizations.config.Messages;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.block.Crafter;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Persistent, craftable offerings for dimension travel. */
public final class DimensionRitualItems implements Listener {
    public enum Ritual {
        NETHER("Nether Ember", Material.FIREWORK_STAR, List.of(Material.OBSIDIAN, Material.OBSIDIAN,
            Material.AMETHYST_SHARD, Material.HONEYCOMB, Material.RABBIT_FOOT, Material.GUNPOWDER, Material.GOLD_INGOT)),
        END("End Sigil", Material.ECHO_SHARD, List.of(Material.ENDER_EYE, Material.ENDER_EYE,
            Material.GHAST_TEAR, Material.BLAZE_ROD, Material.PRISMARINE_CRYSTALS, Material.PHANTOM_MEMBRANE,
            Material.DIAMOND, Material.AMETHYST_SHARD));

        private final String displayName;
        private final Material material;
        private final List<Material> ingredients;

        Ritual(String displayName, Material material, List<Material> ingredients) {
            this.displayName = displayName;
            this.material = material;
            this.ingredients = ingredients;
        }

        public String displayName() { return displayName; }
        public List<Material> ingredients() { return ingredients; }
        public String command() { return name().toLowerCase(Locale.ROOT); }
    }

    private final JavaPlugin plugin;
    private final NamespacedKey marker;

    public DimensionRitualItems(JavaPlugin plugin) {
        this.plugin = plugin;
        marker = new NamespacedKey(plugin, "dimension_ritual");
    }

    public ItemStack create(Ritual ritual) {
        ItemStack item = new ItemStack(ritual.material);
        var meta = item.getItemMeta();
        meta.setDisplayName(Messages.color("&d" + ritual.displayName));
        meta.setLore(List.of(Messages.color("&7Offering for /" + ritual.command() + "."),
            Messages.color("&7Requires expedition technology."),
            Messages.color("&7One consumed per successful trip."),
            Messages.color("&8Recipe: /civ items > Other Items")));
        meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, ritual.command());
        item.setItemMeta(meta);
        return item;
    }

    public boolean matches(ItemStack item, Ritual ritual) {
        return item != null && item.getAmount() > 0 && item.getType() == ritual.material && item.hasItemMeta()
            && ritual.command().equals(item.getItemMeta().getPersistentDataContainer().get(marker, PersistentDataType.STRING));
    }

    public boolean has(Player player, Ritual ritual) { return findSlot(player, ritual) >= 0; }

    /** Debit immediately before teleport callbacks, so they cannot transfer the offering away. */
    public Offering take(Player player, Ritual ritual) {
        int slot = findSlot(player, ritual);
        if (slot < 0) return null;
        ItemStack original = player.getInventory().getItem(slot);
        ItemStack offering = original.clone();
        offering.setAmount(1);
        if (original.getAmount() == 1) player.getInventory().setItem(slot, null);
        else {
            ItemStack remainder = original.clone();
            remainder.setAmount(original.getAmount() - 1);
            player.getInventory().setItem(slot, remainder);
        }
        return new Offering(player, slot, offering);
    }

    private int findSlot(Player player, Ritual ritual) {
        for (int slot = 0; slot < 36; slot++) {
            if (matches(player.getInventory().getItem(slot), ritual)) return slot;
        }
        return matches(player.getInventory().getItem(40), ritual) ? 40 : -1;
    }

    public record Offering(Player player, int slot, ItemStack item) {
        public void refund() {
            ItemStack current = player.getInventory().getItem(slot);
            if (current == null || current.getType() == Material.AIR) player.getInventory().setItem(slot, item);
            else player.getInventory().addItem(item).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }

    public void registerRecipes() {
        for (Ritual ritual : Ritual.values()) {
            NamespacedKey key = recipeKey(ritual);
            plugin.getServer().removeRecipe(key);
            ShapelessRecipe recipe = new ShapelessRecipe(key, create(ritual));
            ritual.ingredients.forEach(recipe::addIngredient);
            plugin.getServer().addRecipe(recipe);
        }
        plugin.getServer().getOnlinePlayers().forEach(this::discover);
    }

    public void unregisterRecipes() {
        for (Ritual ritual : Ritual.values()) plugin.getServer().removeRecipe(recipeKey(ritual));
    }

    private NamespacedKey recipeKey(Ritual ritual) { return new NamespacedKey(plugin, ritual.command() + "_ritual"); }
    private void discover(Player player) {
        for (Ritual ritual : Ritual.values()) player.discoverRecipe(recipeKey(ritual));
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { discover(event.getPlayer()); }

    // Rituals are offerings, never vanilla firework/recovery-compass ingredients.
    private boolean containsRitual(ItemStack[] matrix) {
        return Arrays.stream(matrix).anyMatch(item -> item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(marker, PersistentDataType.STRING));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (containsRitual(event.getInventory().getMatrix())) event.getInventory().setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (containsRitual(event.getInventory().getMatrix())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafterCraft(CrafterCraftEvent event) {
        if (event.getBlock().getState() instanceof Crafter crafter
            && containsRitual(crafter.getInventory().getContents())) event.setCancelled(true);
    }
}
