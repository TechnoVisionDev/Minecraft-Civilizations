package io.github.empireage.civilizations.listener.progression;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.runtime.DimensionRitualItems;
import io.github.empireage.civilizations.runtime.DimensionRitualItems.Ritual;
import io.github.empireage.civilizations.service.progression.CivicItemService;
import io.github.empireage.civilizations.service.progression.ResearchService;
import io.github.empireage.civilizations.service.progression.WorkOrderService;
import io.github.empireage.civilizations.testutil.BukkitItemMocks;
import io.github.empireage.civilizations.config.ResourceCatalog;
import io.github.empireage.civilizations.config.TechnologyCatalog;
import io.github.empireage.civilizations.domain.ResourceKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.mockito.MockedStatic;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProgressionGuiTest {
    @Test
    void technologyCostsUseCustomItemNamesInsteadOfInternalKeys() {
        ResourceCatalog resources = ResourceCatalog.load(new File("src/main/resources/resources.yml"));

        assertEquals("Heavy Cobblestone",
            ProgressionGui.resourceName(resources, ResourceKey.parse("masonry:1")));
        assertEquals("Bound Timber",
            ProgressionGui.resourceName(resources, ResourceKey.parse("timber:1")));
        assertEquals("Arcane Matrix",
            ProgressionGui.resourceName(resources, ResourceKey.parse("arcana:1")));
    }

    @Test
    void everyPackagedTechnologyHasAUniqueNonPlaceholderIcon() {
        ResourceCatalog resources = ResourceCatalog.load(new File("src/main/resources/resources.yml"));
        TechnologyCatalog technologies = TechnologyCatalog.load(new File("src/main/resources/technologies.yml"), resources);
        Set<Material> icons = technologies.technologies().keySet().stream()
            .map(ProgressionGui::technologyIcon).collect(Collectors.toSet());

        assertEquals(technologies.technologies().size(), icons.size());
        assertFalse(icons.contains(Material.BLACK_STAINED_GLASS_PANE));
        assertFalse(icons.contains(Material.GRAY_STAINED_GLASS_PANE));
        assertFalse(icons.contains(Material.KNOWLEDGE_BOOK));
    }

    @Test
    void otherItemsShowBothOfferingsAndExactRecipesWithSafeNavigation() {
        try (ItemMenuHarness h = new ItemMenuHarness()) {
            h.gui.openCustomItems(h.player);
            assertEquals("Other Items", h.name(22));
            h.click(22);
            assertEquals("Nether Ember", h.name(10));
            assertEquals("End Sigil", h.name(11));
            int[] grid = {10, 11, 12, 19, 20, 21, 28, 29, 30};
            for (Ritual ritual : Ritual.values()) {
                h.click(10 + ritual.ordinal());
                assertTrue(h.rituals.matches(h.inventory.getItem(25), ritual));
                for (int index = 0; index < ritual.ingredients().size(); index++) {
                    ItemStack ingredient = h.inventory.getItem(grid[index]);
                    assertEquals(ritual.ingredients().get(index), ingredient.getType());
                    assertEquals(1, ingredient.getAmount());
                }
                for (int index = ritual.ingredients().size(); index < grid.length; index++) {
                    assertEquals(Material.BLACK_STAINED_GLASS_PANE, h.inventory.getItem(grid[index]).getType());
                }
                Inventory recipe = h.inventory;
                h.click(25);
                h.click(50);
                assertEquals(recipe, h.inventory);
                InventoryDragEvent drag = mock(InventoryDragEvent.class);
                when(drag.getView()).thenReturn(h.view);
                h.gui.onDrag(drag);
                verify(drag).setCancelled(true);
                h.click(40);
                assertEquals("Nether Ember", h.name(10));
            }
            assertEquals("War Charter", h.name(12));
            assertEquals("Civilizations Tutorial", h.name(13));
            h.click(12);
            Material[] charterRecipe = {Material.PAPER, Material.IRON_INGOT, Material.PAPER,
                Material.IRON_INGOT, Material.WRITABLE_BOOK, Material.IRON_INGOT,
                Material.PAPER, Material.IRON_INGOT, Material.PAPER};
            for (int index = 0; index < grid.length; index++) {
                assertEquals(charterRecipe[index], h.inventory.getItem(grid[index]).getType());
                assertEquals(1, h.inventory.getItem(grid[index]).getAmount());
            }
            assertEquals("War Charter", h.name(25));
            assertEquals("charter", h.inventory.getItem(25).getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey("civilizations", "war_item"), PersistentDataType.STRING));
            assertTrue(h.inventory.getItem(23).getItemMeta().getLore().stream()
                .anyMatch(line -> line.contains("Shaped recipe")));
            h.click(40);
            h.click(13);
            String tutorialLore = String.join("\n", h.inventory.getItem(22).getItemMeta().getLore());
            assertTrue(tutorialLore.contains("/civ tutorial"));
            assertTrue(tutorialLore.contains("24 hours"));
            assertTrue(tutorialLore.contains("no crafting recipe"));
            h.click(40);
            h.click(31);
            assertEquals("Other Items", h.name(22));
            h.click(11);
            h.click(10);
            assertEquals("Arcane Matrix", h.name(25));
            h.click(40);
            h.click(31);
            assertEquals("Other Items", h.name(22));
        }
    }

    private static final class ItemMenuHarness implements AutoCloseable {
        private final BukkitItemMocks itemMocks = new BukkitItemMocks();
        private final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        private final Player player = mock(Player.class);
        private final InventoryView view = mock(InventoryView.class);
        private final CivicItemService items;
        private final DimensionRitualItems rituals;
        private final ProgressionGui gui;
        private Inventory inventory;

        private ItemMenuHarness() {
            JavaPlugin plugin = mock(JavaPlugin.class);
            when(plugin.getName()).thenReturn("Civilizations");
            when(view.getTopInventory()).thenAnswer(call -> inventory);
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), anyInt(), anyString())).thenAnswer(call -> {
                Inventory result = mock(Inventory.class);
                Map<Integer, ItemStack> contents = new HashMap<>();
                when(result.getHolder()).thenReturn(call.getArgument(0));
                when(result.getSize()).thenReturn(call.getArgument(1));
                when(result.getItem(anyInt())).thenAnswer(read -> contents.get(read.<Integer>getArgument(0)));
                doAnswer(write -> { contents.put(write.getArgument(0), write.getArgument(1)); return null; })
                    .when(result).setItem(anyInt(), any());
                inventory = result;
                return result;
            });
            ResourceCatalog resources = ResourceCatalog.load(new File("src/main/resources/resources.yml"));
            items = new CivicItemService(plugin, resources);
            rituals = new DimensionRitualItems(plugin);
            gui = new ProgressionGui(plugin, mock(StateCache.class), mock(TechnologyCatalog.class), resources,
                items, mock(ResearchService.class), mock(WorkOrderService.class));
        }

        private String name(int slot) {
            return ChatColor.stripColor(inventory.getItem(slot).getItemMeta().getDisplayName());
        }

        private void click(int slot) {
            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getView()).thenReturn(view);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getRawSlot()).thenReturn(slot);
            gui.onClick(event);
            verify(event).setCancelled(true);
        }

        @Override public void close() {
            bukkit.close();
            itemMocks.close();
        }
    }

}
