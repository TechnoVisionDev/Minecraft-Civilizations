package io.github.empireage.civilizations.service.progression;

import io.github.empireage.civilizations.config.ResourceCatalog;
import io.github.empireage.civilizations.config.ResourceText;
import io.github.empireage.civilizations.domain.ResourceKey;
import io.github.empireage.civilizations.testutil.BukkitItemMocks;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CivicItemServiceTest {
    private final ResourceCatalog resources = catalogWithoutServerRegistries();
    private final JavaPlugin plugin = mock(JavaPlugin.class);

    private CivicItemService service() {
        when(plugin.getName()).thenReturn("Civilizations");
        return new CivicItemService(plugin, resources);
    }

    private static ResourceCatalog catalogWithoutServerRegistries() {
        ResourceCatalog catalog = ResourceCatalog.load(new File("src/main/resources/resources.yml"));
        java.util.Map<ResourceKey, ResourceCatalog.Tier> tiers = new java.util.HashMap<>();
        // Material.isAir() in this API consults a live server registry. Stub only that external boundary.
        catalog.tiers().forEach((key, tier) -> {
            Material material = mock(Material.class);
            when(material.isAir()).thenReturn(false);
            tiers.put(key, new ResourceCatalog.Tier(key, material, tier.displayName(), tier.modelData()));
        });
        return new ResourceCatalog(catalog.recipeVersion(), catalog.creativeDeposits(), catalog.higherTierRatio(),
            java.util.Map.copyOf(tiers), catalog.baseRecipes(), catalog.claimCostBands());
    }

    @Test
    void createsAuthenticItemsWithConsistentTierNamesAcrossAllFamilies() {
        CivicItemService items = service();
        try (var ignored = new BukkitItemMocks()) {
            for (ResourceKey key : resources.tiers().keySet()) {
                ItemStack item = items.create(key, 12);
                assertEquals(ResourceText.itemName(resources, key), item.getItemMeta().getDisplayName());
                assertEquals(key, items.identify(item).orElseThrow());
                assertEquals(12, item.getAmount());
            }
        }
    }

    @Test
    void legacyColorsRemainValidAndRecoloringPreservesQuantityLoreAndIdentity() {
        CivicItemService items = service();
        ResourceKey key = ResourceKey.parse("masonry:1");
        try (var ignored = new BukkitItemMocks()) {
            ItemStack item = items.create(key, 32);
            item.getItemMeta().setDisplayName(ChatColor.GRAY + "Heavy Cobblestone");
            item.getItemMeta().setLore(List.of("Existing lore"));
            assertEquals(key, items.identify(item).orElseThrow());
            assertTrue(items.normalizeName(item));
            assertEquals(ChatColor.GREEN + "Heavy Cobblestone", item.getItemMeta().getDisplayName());
            assertEquals(32, item.getAmount());
            assertEquals(List.of("Existing lore"), item.getItemMeta().getLore());
            assertEquals(key, items.identify(item).orElseThrow());
            assertFalse(items.normalizeName(item));
        }
    }

    @Test
    void colorCompatibilityDoesNotAuthenticateSpoofedRenamedOrObsoleteItems() {
        CivicItemService items = service();
        ResourceKey key = ResourceKey.parse("masonry:1");
        try (var ignored = new BukkitItemMocks()) {
            ItemStack forged = new ItemStack(resources.require(key).material());
            forged.getItemMeta().setDisplayName(ResourceText.itemName(resources, key));
            assertTrue(items.identify(forged).isEmpty());
            assertFalse(items.normalizeName(forged));

            ItemStack renamed = items.create(key, 1);
            renamed.getItemMeta().setDisplayName(ChatColor.GREEN + "Fake Cobblestone");
            assertTrue(items.identify(renamed).isEmpty());
            assertFalse(items.normalizeName(renamed));

            ItemStack obsolete = items.create(key, 1);
            obsolete.getItemMeta().getPersistentDataContainer().set(new NamespacedKey(plugin, "civic-recipe-version"),
                PersistentDataType.INTEGER, resources.recipeVersion() + 1);
            assertTrue(items.identify(obsolete).isEmpty());
            assertFalse(items.normalizeName(obsolete));

            ItemStack wrongMaterial = items.create(key, 1);
            when(wrongMaterial.getType()).thenReturn(mock(Material.class));
            assertTrue(items.identify(wrongMaterial).isEmpty());
        }
    }
}
