package io.github.empireage.civilizations.service.progression;

import io.github.empireage.civilizations.domain.TechnologyMode;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TechnologyGatePolicyTest {
    @Test
    void plainWaterCanBeDrunkWithoutAlchemy() {
        ItemStack water = potion(Material.POTION, PotionType.WATER, false);
        assertTrue(CapabilityPolicy.requirementsForUse(water).isEmpty());
    }

    @Test
    void otherDrinkablePotionsStillRequireAlchemy() {
        for (PotionType type : PotionType.values()) {
            if (type == PotionType.WATER) continue;
            assertEquals(Set.of(CapabilityPolicy.USE_DRINKABLE_POTIONS),
                CapabilityPolicy.requirementsForUse(potion(Material.POTION, type, false)), type.name());
        }
        assertEquals(Set.of(CapabilityPolicy.USE_DRINKABLE_POTIONS),
            CapabilityPolicy.requirementsForUse(potion(Material.POTION, PotionType.WATER, true)));
        assertEquals(Set.of(CapabilityPolicy.USE_DRINKABLE_POTIONS),
            CapabilityPolicy.requirementsForUse(potion(Material.POTION, null, false)));
    }

    @Test
    void throwableWaterStillRequiresAdvancedAlchemy() {
        assertEquals(Set.of("USE_SPLASH_POTIONS"),
            CapabilityPolicy.requirementsForUse(potion(Material.SPLASH_POTION, PotionType.WATER, false)));
        assertEquals(Set.of("USE_LINGERING_POTIONS"),
            CapabilityPolicy.requirementsForUse(potion(Material.LINGERING_POTION, PotionType.WATER, false)));
    }

    private static ItemStack potion(Material material, PotionType type, boolean customEffects) {
        ItemStack item = mock(ItemStack.class);
        PotionMeta meta = mock(PotionMeta.class);
        when(item.getType()).thenReturn(material);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getBasePotionType()).thenReturn(type);
        when(meta.hasCustomEffects()).thenReturn(customEffects);
        return item;
    }

    @Test
    void enforcementModesSeparateProductionFromUse() {
        assertTrue(TechnologyAccess.productionGated(TechnologyMode.STRICT));
        assertTrue(TechnologyAccess.productionGated(TechnologyMode.CRAFT_ONLY));
        assertFalse(TechnologyAccess.productionGated(TechnologyMode.DISABLED));
        assertTrue(TechnologyAccess.useGated(TechnologyMode.STRICT));
        assertFalse(TechnologyAccess.useGated(TechnologyMode.CRAFT_ONLY));
        assertFalse(TechnologyAccess.useGated(TechnologyMode.DISABLED));
    }

    @Test
    void equipmentStationsTravelAndFlightMapToTheirCapabilities() {
        Material copperPickaxe = Material.matchMaterial("COPPER_PICKAXE");
        Material copperChestplate = Material.matchMaterial("COPPER_CHESTPLATE");
        assertTrue(copperPickaxe != null, "Paper 26.2 must expose copper tools");
        assertTrue(copperChestplate != null, "Paper 26.2 must expose copper armor");
        assertEquals(Set.of("USE_COPPER_TOOLS"),
            CapabilityPolicy.requirementsForUse(new ItemStack(copperPickaxe)));
        assertEquals(Set.of("EQUIP_COPPER_ARMOR"),
            CapabilityPolicy.requirementsForUse(new ItemStack(copperChestplate)));
        assertEquals(Set.of("USE_IRON_TOOLS"),
            CapabilityPolicy.requirementsForUse(new ItemStack(Material.IRON_PICKAXE)));
        assertEquals(Set.of("CRAFT_DIAMOND_ARMOR"),
            CapabilityPolicy.requirementsForProduction(new ItemStack(Material.DIAMOND_CHESTPLATE)));
        assertTrue(CapabilityPolicy.requirementsForStation(Material.SMITHING_TABLE).isEmpty());
        assertEquals(Set.of("USE_SPLASH_POTIONS"),
            CapabilityPolicy.requirementsForUse(new ItemStack(Material.SPLASH_POTION)));
        assertEquals(Set.of(CapabilityPolicy.EQUIP_ELYTRA),
            CapabilityPolicy.requirementsForUse(new ItemStack(Material.ELYTRA)));
        assertEquals(Set.of("MECHANICAL_TRANSPORT", "REDSTONE_ENGINEERING"),
            CapabilityPolicy.requirementsForProduction(new ItemStack(Material.POWERED_RAIL)));
        assertEquals(Set.of("USE_ADVANCED_REDSTONE"),
            CapabilityPolicy.requirementsForStation(Material.REPEATER));
        assertEquals(Set.of("USE_TNT"), CapabilityPolicy.requirementsForStation(Material.TNT));
        assertEquals(Set.of("USE_BASIC_MINECARTS"), CapabilityPolicy.requirementsForVehicle("MINECART"));
        assertEquals(Set.of("USE_ADVANCED_MINECARTS"), CapabilityPolicy.requirementsForVehicle("HOPPER_MINECART"));
        assertEquals(Set.of("USE_TNT"), CapabilityPolicy.requirementsForVehicle("TNT_MINECART"));
        assertEquals(Set.of("USE_CHEST_BOATS"), CapabilityPolicy.requirementsForVehicle("OAK_CHEST_BOAT"));
        assertTrue(CapabilityPolicy.requirementsForUse(new ItemStack(Material.STONE_PICKAXE)).isEmpty());
        assertTrue(CapabilityPolicy.requirementsForProduction(new ItemStack(Material.LEVER)).isEmpty());
    }
}
