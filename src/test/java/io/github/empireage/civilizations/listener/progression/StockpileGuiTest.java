package io.github.empireage.civilizations.listener.progression;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.config.ResourceCatalog;
import io.github.empireage.civilizations.config.ResourceText;
import io.github.empireage.civilizations.config.TechnologyCatalog;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.ResourceKey;
import io.github.empireage.civilizations.service.progression.CivicItemService;
import io.github.empireage.civilizations.service.progression.ResearchService;
import io.github.empireage.civilizations.service.progression.WorkOrderService;
import io.github.empireage.civilizations.testutil.BukkitItemMocks;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockpileGuiTest {
    private final ResourceCatalog resources = ResourceCatalog.load(new File("src/main/resources/resources.yml"));

    @Test
    void allResourcesShowMatchingItemNamesExactTotalsAndSafeStackCounts() {
        Map<ResourceKey, Long> balances = Map.of(ResourceKey.parse("arcana:1"), 1L,
            ResourceKey.parse("arcana:2"), 23L, ResourceKey.parse("arcana:3"), 64L,
            ResourceKey.parse("masonry:1"), 65L, ResourceKey.parse("masonry:2"), Long.MAX_VALUE);
        try (Harness h = new Harness(resources)) {
            h.gui.openStockpile(h.player, 7, balances);
            String[] families = {"arcana", "masonry", "metalwork", "scholarship", "timber"};
            for (int column = 0; column < families.length; column++) {
                for (int tier = 1; tier <= 3; tier++) {
                    ResourceKey key = new ResourceKey(families[column], tier);
                    ItemStack icon = h.inventory.getItem(tier * 9 + column + 1);
                    long balance = balances.getOrDefault(key, 0L);
                    assertEquals(resources.require(key).material(), icon.getType());
                    assertEquals(h.items.create(key, 1).getItemMeta().getDisplayName(), icon.getItemMeta().getDisplayName());
                    assertEquals(Math.max(1L, Math.min(64L, balance)), icon.getAmount());
                    String lore = String.join("\n", icon.getItemMeta().getLore());
                    assertTrue(lore.contains(String.format(java.util.Locale.US, "%,d", balance)));
                    assertTrue(lore.contains("Craft one from:"));
                    if (balance == 0) assertTrue(lore.contains("None in stockpile"));
                    if (balance > 64) assertTrue(lore.contains("capped at 64"));
                }
            }
            String compression = ChatColor.stripColor(String.join("\n", h.inventory.getItem(20).getItemMeta().getLore()));
            assertTrue(compression.contains("9 Heavy Cobblestone"));
            assertFalse(compression.contains("masonry:1"));
        }
    }

    @Test
    void stockpileCancelsAllInventoryTransfersAndRefreshReloadsThroughTheCommand() {
        try (Harness h = new Harness(resources)) {
            h.gui.openStockpile(h.player, 7, Map.of());
            for (ClickType type : new ClickType[]{ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY,
                ClickType.DOUBLE_CLICK, ClickType.DROP, ClickType.SWAP_OFFHAND}) {
                for (int slot : new int[]{10, 60, -999}) {
                    InventoryClickEvent event = h.click(slot);
                    when(event.getClick()).thenReturn(type);
                    h.gui.onClick(event);
                    verify(event).setCancelled(true);
                }
            }
            InventoryDragEvent drag = mock(InventoryDragEvent.class);
            when(drag.getView()).thenReturn(h.view);
            h.gui.onDrag(drag);
            verify(drag).setCancelled(true);
            h.gui.onClick(h.click(50));
            verify(h.player).performCommand("civ stockpile");
        }
    }

    @Test
    void additionalFamiliesAreReachableByPageAndRetainTheirBalances() {
        Map<ResourceKey, ResourceCatalog.Tier> tiers = new HashMap<>();
        for (int family = 0; family < 8; family++) for (int tier = 1; tier <= 3; tier++) {
            ResourceKey key = new ResourceKey("family_" + family, tier);
            tiers.put(key, new ResourceCatalog.Tier(key, Material.PAPER, "Family " + family + " Tier " + tier, 0));
        }
        ResourceCatalog catalog = new ResourceCatalog(1, false, 9, Map.copyOf(tiers), Map.of(), java.util.List.of());
        ResourceKey last = new ResourceKey("family_7", 3);
        try (Harness h = new Harness(catalog)) {
            h.gui.openStockpile(h.player, 7, Map.of(last, 42L));
            assertEquals(Material.ARROW, h.inventory.getItem(53).getType());
            h.gui.onClick(h.click(53));
            assertEquals(ResourceText.itemName(catalog, last), h.inventory.getItem(28).getItemMeta().getDisplayName());
            assertEquals(42, h.inventory.getItem(28).getAmount());
            h.gui.onClick(h.click(45));
            assertEquals("Family 0 Tier 1", ChatColor.stripColor(h.inventory.getItem(10).getItemMeta().getDisplayName()));
        }
    }

    @Test
    void asyncResultDoesNotOpenForDisconnectedPlayersOrChangedMembership() {
        try (Harness h = new Harness(resources)) {
            when(h.player.isOnline()).thenReturn(false);
            h.gui.openStockpile(h.player, 7, Map.of());
            verify(h.player, never()).openInventory(any(Inventory.class));
            when(h.player.isOnline()).thenReturn(true);
            when(h.member.civilizationId()).thenReturn(8L);
            h.gui.openStockpile(h.player, 7, Map.of());
            verify(h.player, never()).openInventory(any(Inventory.class));
        }
    }

    private static final class Harness implements AutoCloseable {
        private final BukkitItemMocks itemMocks = new BukkitItemMocks();
        private final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        private final Player player = mock(Player.class);
        private final Member member = mock(Member.class);
        private final InventoryView view = mock(InventoryView.class);
        private final CivicItemService items;
        private final ProgressionGui gui;
        private Inventory inventory;

        private Harness(ResourceCatalog catalog) {
            JavaPlugin plugin = mock(JavaPlugin.class);
            when(plugin.getName()).thenReturn("Civilizations");
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.isOnline()).thenReturn(true);
            when(player.getOpenInventory()).thenReturn(view);
            when(view.getTopInventory()).thenAnswer(call -> inventory);
            StateCache cache = mock(StateCache.class);
            StateSnapshot snapshot = mock(StateSnapshot.class);
            when(cache.snapshot()).thenReturn(snapshot);
            when(snapshot.member(player.getUniqueId())).thenReturn(member);
            when(member.civilizationId()).thenReturn(7L);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                call.<Runnable>getArgument(1).run();
                return null;
            });
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
            items = new CivicItemService(plugin, catalog);
            gui = new ProgressionGui(plugin, cache, mock(TechnologyCatalog.class), catalog, items,
                mock(ResearchService.class), mock(WorkOrderService.class));
        }

        private InventoryClickEvent click(int slot) {
            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getView()).thenReturn(view);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getRawSlot()).thenReturn(slot);
            return event;
        }

        @Override
        public void close() {
            bukkit.close();
            itemMocks.close();
        }
    }
}
