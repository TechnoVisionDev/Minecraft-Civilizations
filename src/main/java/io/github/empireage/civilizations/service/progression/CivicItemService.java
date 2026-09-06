package io.github.empireage.civilizations.service.progression;

import io.github.empireage.civilizations.config.ResourceCatalog;
import io.github.empireage.civilizations.config.ResourceText;
import io.github.empireage.civilizations.domain.ResourceKey;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Creates and authenticates the physical civic-resource tokens. */
public final class CivicItemService {
    private static final byte MARKER_VALUE = 1;

    private final JavaPlugin plugin;
    private final ResourceCatalog catalog;
    private final NamespacedKey markerKey;
    private final NamespacedKey familyKey;
    private final NamespacedKey tierKey;
    private final NamespacedKey recipeVersionKey;
    private final List<NamespacedKey> recipeKeys = new ArrayList<>();

    public CivicItemService(JavaPlugin plugin, ResourceCatalog catalog) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        markerKey = new NamespacedKey(plugin, "civic-item");
        familyKey = new NamespacedKey(plugin, "civic-family");
        tierKey = new NamespacedKey(plugin, "civic-tier");
        recipeVersionKey = new NamespacedKey(plugin, "civic-recipe-version");
    }

    public ItemStack create(ResourceKey key, int amount) {
        if (amount < 1) throw new IllegalArgumentException("amount must be positive");
        ResourceCatalog.Tier definition = catalog.require(key);
        ItemStack item = new ItemStack(definition.material(), amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ResourceText.itemName(catalog, key));
        if (definition.modelData() > 0) meta.setCustomModelData(definition.modelData());
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(markerKey, PersistentDataType.BYTE, MARKER_VALUE);
        data.set(familyKey, PersistentDataType.STRING, key.family());
        data.set(tierKey, PersistentDataType.INTEGER, key.tier());
        data.set(recipeVersionKey, PersistentDataType.INTEGER, catalog.recipeVersion());
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Returns a key only when both the server-only PDC identity and the configured
     * physical representation match. Names and material alone never authenticate a token.
     */
    public Optional<ResourceKey> identify(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Optional.empty();
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        Byte marker = data.get(markerKey, PersistentDataType.BYTE);
        String family = data.get(familyKey, PersistentDataType.STRING);
        Integer tier = data.get(tierKey, PersistentDataType.INTEGER);
        Integer version = data.get(recipeVersionKey, PersistentDataType.INTEGER);
        if (marker == null || marker != MARKER_VALUE || family == null || tier == null || version == null
            || version != catalog.recipeVersion()) return Optional.empty();
        final ResourceKey key;
        try {
            key = new ResourceKey(family, tier);
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
        ResourceCatalog.Tier definition = catalog.tiers().get(key);
        if (definition == null || item.getType() != definition.material()) return Optional.empty();
        if (definition.modelData() > 0
            && (!meta.hasCustomModelData() || meta.getCustomModelData() != definition.modelData())) return Optional.empty();
        if (definition.modelData() == 0 && meta.hasCustomModelData()) return Optional.empty();
        // Color-only changes must not invalidate already-issued, authenticated tokens.
        if (!meta.hasDisplayName()
            || !ResourceText.name(catalog, key).equals(ChatColor.stripColor(meta.getDisplayName()))) return Optional.empty();
        return Optional.of(key);
    }

    /** Updates only the appearance of an authentic token, preserving its identity and other metadata. */
    public boolean normalizeName(ItemStack item) {
        ResourceKey key = identify(item).orElse(null);
        if (key == null) return false;
        ItemMeta meta = item.getItemMeta();
        String name = ResourceText.itemName(catalog, key);
        if (name.equals(meta.getDisplayName())) return false;
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return true;
    }

    public void normalizeNames(Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (normalizeName(item)) inventory.setItem(slot, item);
        }
    }

    /** True when an item claims to be civic, including malformed/obsolete tokens. */
    public boolean isCivicCandidate(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        return data.has(markerKey, PersistentDataType.BYTE)
            || data.has(familyKey, PersistentDataType.STRING)
            || data.has(tierKey, PersistentDataType.INTEGER)
            || data.has(recipeVersionKey, PersistentDataType.INTEGER);
    }

    public Map<ResourceKey, Long> countValid(Iterable<ItemStack> items) {
        Map<ResourceKey, Long> result = new LinkedHashMap<>();
        for (ItemStack item : items) identify(item).ifPresent(key ->
            result.merge(key, (long) item.getAmount(), Math::addExact));
        return Map.copyOf(result);
    }

    public void registerRecipes() {
        unregisterRecipes();
        catalog.baseRecipes().values().stream()
            .sorted(Comparator.comparing(ResourceCatalog.BaseRecipe::family))
            .forEach(this::registerBaseRecipe);
        Set<String> families = catalog.tiers().keySet().stream().map(ResourceKey::family)
            .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        for (String family : families) {
            for (int tier = 1; tier < 3; tier++) {
                ResourceKey lower = new ResourceKey(family, tier);
                ResourceKey upper = new ResourceKey(family, tier + 1);
                if (!catalog.tiers().containsKey(lower) || !catalog.tiers().containsKey(upper)) continue;
                if (catalog.higherTierRatio() > 9) continue; // a 3x3 crafting grid cannot represent this; the GUI still can
                NamespacedKey recipeKey = recipeKey(family + "-tier-" + (tier + 1));
                ShapelessRecipe recipe = new ShapelessRecipe(recipeKey, create(upper, 1));
                RecipeChoice.ExactChoice choice = new RecipeChoice.ExactChoice(create(lower, 1));
                for (int ingredient = 0; ingredient < catalog.higherTierRatio(); ingredient++) recipe.addIngredient(choice);
                plugin.getServer().addRecipe(recipe);
            }
        }
    }

    public void unregisterRecipes() {
        for (NamespacedKey key : recipeKeys) plugin.getServer().removeRecipe(key);
        recipeKeys.clear();
    }

    private void registerBaseRecipe(ResourceCatalog.BaseRecipe definition) {
        ResourceKey output = new ResourceKey(definition.family(), 1);
        if (!catalog.tiers().containsKey(output)) return;
        NamespacedKey key = recipeKey(definition.family() + "-tier-1");
        ShapelessRecipe recipe = new ShapelessRecipe(key, create(output, 1));
        if (!definition.acceptedAlternatives().isEmpty()) {
            RecipeChoice.MaterialChoice choice = new RecipeChoice.MaterialChoice(List.copyOf(definition.acceptedAlternatives()));
            for (int ingredient = 0; ingredient < 9; ingredient++) recipe.addIngredient(choice);
        } else {
            definition.ingredients().forEach((material, amount) -> recipe.addIngredient(amount, material));
        }
        plugin.getServer().addRecipe(recipe);
    }

    private NamespacedKey recipeKey(String suffix) {
        NamespacedKey key = new NamespacedKey(plugin, "civic-" + suffix.replace('_', '-'));
        recipeKeys.add(key);
        return key;
    }

    public ResourceCatalog catalog() {
        return catalog;
    }
}
