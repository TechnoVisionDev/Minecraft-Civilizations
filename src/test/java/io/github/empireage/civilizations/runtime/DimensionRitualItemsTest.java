package io.github.empireage.civilizations.runtime;

import io.github.empireage.civilizations.runtime.DimensionRitualItems.Ritual;
import io.github.empireage.civilizations.testutil.BukkitItemMocks;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Crafter;
import org.bukkit.entity.Player;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DimensionRitualItemsTest {
    final JavaPlugin plugin = mock(JavaPlugin.class);
    final Player player = mock(Player.class);
    final PlayerInventory inventory = mock(PlayerInventory.class);
    final Map<Integer, ItemStack> slots = new HashMap<>();
    DimensionRitualItems items;

    @BeforeEach void setup() {
        when(plugin.getName()).thenReturn("Civilizations");
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItem(anyInt())).thenAnswer(call -> slots.get(call.getArgument(0)));
        doAnswer(call -> { slots.put(call.getArgument(0), call.getArgument(1)); return null; })
            .when(inventory).setItem(anyInt(), any());
        items = new DimensionRitualItems(plugin);
    }

    @Test void vanillaRenamedWrongMaterialWrongDimensionAndEmptyItemsCannotAuthorizeTravel() {
        try (var ignored = new BukkitItemMocks()) {
            for (Ritual ritual : Ritual.values()) {
                ItemStack real = items.create(ritual);
                assertTrue(items.matches(real, ritual));
                assertFalse(items.matches(real, ritual == Ritual.END ? Ritual.NETHER : Ritual.END));
                ItemStack forged = new ItemStack(real.getType());
                forged.getItemMeta().setDisplayName(real.getItemMeta().getDisplayName());
                assertFalse(items.matches(forged, ritual));
                real.setAmount(0);
                assertFalse(items.matches(real, ritual));
                real.setAmount(1);
                when(real.getType()).thenReturn(Material.STONE);
                assertFalse(items.matches(real, ritual));
            }
            assertFalse(items.has(player, Ritual.END));
            assertNull(items.take(player, Ritual.END));
        }
    }

    @Test void removesExactlyOneFromStorageOrOffhandAndRefundsFailedTravel() {
        try (var ignored = new BukkitItemMocks()) {
            ItemStack stack = cloneable(items.create(Ritual.END));
            stack.setAmount(3);
            slots.put(12, stack);
            var offering = items.take(player, Ritual.END);
            assertEquals(1, offering.item().getAmount());
            assertEquals(2, slots.get(12).getAmount());
            assertTrue(items.matches(slots.get(12), Ritual.END));
            when(inventory.addItem(offering.item())).thenReturn(new HashMap<>());
            offering.refund();
            verify(inventory).addItem(offering.item());
            slots.clear();
            slots.put(40, cloneable(items.create(Ritual.NETHER)));
            assertTrue(items.has(player, Ritual.NETHER));
            var offhand = items.take(player, Ritual.NETHER);
            assertNull(slots.get(40));
            offhand.refund();
            assertTrue(items.matches(slots.get(40), Ritual.NETHER));
            assertEquals(1, slots.get(40).getAmount());
        }
    }

    @Test void refundNeverOverwritesItemsAddedByTeleportCallbacksAndDropsOverflow() {
        try (var ignored = new BukkitItemMocks()) {
            slots.put(0, cloneable(items.create(Ritual.NETHER)));
            var offering = items.take(player, Ritual.NETHER);
            ItemStack other = new ItemStack(Material.DIAMOND);
            slots.put(0, other);
            when(inventory.addItem(offering.item())).thenReturn(new HashMap<>(Map.of(0, offering.item())));
            World world = mock(World.class);
            Location location = new Location(world, 0, 65, 0);
            when(player.getWorld()).thenReturn(world);
            when(player.getLocation()).thenReturn(location);
            offering.refund();
            assertSame(other, slots.get(0));
            verify(world).dropItemNaturally(location, offering.item());
        }
    }

    @Test void recipesProduceDistinctOfferingsRegisterAllIngredientsAndAreDiscovered() {
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getOnlinePlayers()).thenAnswer(call -> List.of(player));
        try (var ignored = new BukkitItemMocks(); var recipes = mockConstruction(ShapelessRecipe.class, (recipe, context) -> {
            Ritual ritual = context.arguments().getFirst().toString().contains("nether") ? Ritual.NETHER : Ritual.END;
            assertTrue(items.matches((ItemStack) context.arguments().get(1), ritual));
            assertEquals(1, ((ItemStack) context.arguments().get(1)).getAmount());
        })) {
            items.registerRecipes();
            assertEquals(2, recipes.constructed().size());
            for (Ritual ritual : Ritual.values()) {
                ShapelessRecipe recipe = recipes.constructed().get(ritual.ordinal());
                for (Material ingredient : ritual.ingredients().stream().distinct().toList()) {
                    int quantity = (int) ritual.ingredients().stream().filter(ingredient::equals).count();
                    verify(recipe, times(quantity)).addIngredient(ingredient);
                }
                verify(server).addRecipe(recipe);
                verify(player).discoverRecipe(new NamespacedKey(plugin, ritual.command() + "_ritual"));
            }
            assertEquals(7, Ritual.NETHER.ingredients().size());
            assertEquals(8, Ritual.END.ingredients().size());
            items.onJoin(new PlayerJoinEvent(player, "joined"));
            items.unregisterRecipes();
            for (Ritual ritual : Ritual.values()) {
                verify(server, times(2)).removeRecipe(new NamespacedKey(plugin, ritual.command() + "_ritual"));
                verify(player, times(2)).discoverRecipe(new NamespacedKey(plugin, ritual.command() + "_ritual"));
            }
        }
    }

    @Test void ritualIngredientsAreProtectedInCraftingTableAndAutomaticCrafter() {
        try (var ignored = new BukkitItemMocks()) {
            ItemStack ritual = items.create(Ritual.NETHER);
            when(ritual.getItemMeta().getPersistentDataContainer().has(any(), eq(PersistentDataType.STRING))).thenReturn(true);
            CraftingInventory crafting = mock(CraftingInventory.class);
            when(crafting.getMatrix()).thenReturn(new ItemStack[]{null, ritual});
            PrepareItemCraftEvent prepare = mock(PrepareItemCraftEvent.class);
            when(prepare.getInventory()).thenReturn(crafting);
            items.onPrepareCraft(prepare);
            verify(crafting).setResult(null);
            CraftItemEvent craft = mock(CraftItemEvent.class);
            when(craft.getInventory()).thenReturn(crafting);
            items.onCraft(craft);
            verify(craft).setCancelled(true);
            CrafterCraftEvent auto = mock(CrafterCraftEvent.class);
            Block block = mock(Block.class);
            Crafter crafter = mock(Crafter.class);
            Inventory contents = mock(Inventory.class);
            when(auto.getBlock()).thenReturn(block);
            when(block.getState()).thenReturn(crafter);
            when(crafter.getInventory()).thenReturn(contents);
            when(contents.getContents()).thenReturn(new ItemStack[]{ritual});
            items.onCrafterCraft(auto);
            verify(auto).setCancelled(true);
            ItemStack ordinaryIngredient = new ItemStack(Material.GUNPOWDER);
            when(crafting.getMatrix()).thenReturn(new ItemStack[]{ordinaryIngredient});
            clearInvocations(crafting, craft);
            items.onPrepareCraft(prepare);
            items.onCraft(craft);
            verify(crafting, never()).setResult(any());
            verify(craft, never()).setCancelled(anyBoolean());
        }
    }

    private ItemStack cloneable(ItemStack item) {
        when(item.clone()).thenAnswer(call -> {
            ItemStack copy = new ItemStack(item.getType(), item.getAmount());
            var meta = item.getItemMeta();
            when(copy.getItemMeta()).thenReturn(meta);
            return cloneable(copy);
        });
        return item;
    }
}
