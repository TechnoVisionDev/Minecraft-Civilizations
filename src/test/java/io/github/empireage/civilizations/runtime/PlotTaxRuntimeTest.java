package io.github.empireage.civilizations.runtime;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.service.economy.PlotTaxService;
import io.github.empireage.civilizations.testutil.BukkitItemMocks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlotTaxRuntimeTest {
    @Test void taxMenuShowsTheRateAndDueDateAndBlocksAllItemTransfers() {
        try (var items = new BukkitItemMocks(); var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            JavaPlugin plugin = mock(JavaPlugin.class); when(plugin.isEnabled()).thenReturn(true);
            Player player = mock(Player.class); UUID owner = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(owner); when(player.isOnline()).thenReturn(true);
            StateCache cache = mock(StateCache.class); StateSnapshot snapshot = mock(StateSnapshot.class);
            Member member = mock(Member.class); when(member.civilizationId()).thenReturn(1L);
            when(cache.snapshot()).thenReturn(snapshot); when(snapshot.member(owner)).thenReturn(member);
            PlotTaxService taxes = mock(PlotTaxService.class);
            var plot = new PlotTaxService.PlotTaxView(10, "world", 2, 3, "My home", new BigDecimal("5.00"),
                Instant.parse("2026-09-11T12:00:00Z"), null);
            when(taxes.overview(owner)).thenReturn(CompletableFuture.completedFuture(
                new PlotTaxService.TaxOverview(1, new BigDecimal("5.00"), false, List.of(plot), true)));
            Inventory menu = mock(Inventory.class); Map<Integer, ItemStack> contents = new HashMap<>();
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), anyString())).thenAnswer(call -> {
                when(menu.getHolder()).thenReturn(call.getArgument(0));
                return menu;
            });
            doAnswer(call -> { contents.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(menu).setItem(anyInt(), any());
            PlotTaxRuntime runtime = new PlotTaxRuntime(plugin, cache, taxes);
            runtime.open(player);
            verify(player).openInventory(menu);
            assertTrue(contents.get(4).getItemMeta().getLore().stream().anyMatch(line -> line.contains("$5.00 per plot / week")));
            assertTrue(contents.get(10).getItemMeta().getLore().stream().anyMatch(line -> line.contains("Sep 11, 2026 12:00 UTC")));
            InventoryView view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(menu);
            for (int slot : new int[]{10, 60, -999}) {
                InventoryClickEvent event = mock(InventoryClickEvent.class);
                when(event.getView()).thenReturn(view); when(event.getRawSlot()).thenReturn(slot);
                when(event.getWhoClicked()).thenReturn(player);
                runtime.onClick(event); verify(event).setCancelled(true);
            }
            InventoryDragEvent drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(view);
            runtime.onDrag(drag); verify(drag).setCancelled(true);
        }
    }
}
