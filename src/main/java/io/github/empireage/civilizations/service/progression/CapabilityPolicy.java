package io.github.empireage.civilizations.service.progression;

import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Explicit craft/use mappings for every technology-gated vanilla item. */
public final class CapabilityPolicy {
    public static final String ENCHANT_ITEMS = "ENCHANT_ITEMS";
    public static final String EQUIP_ENCHANTED_ITEMS = "EQUIP_ENCHANTED_ITEMS";
    public static final String USE_BREWING_STANDS = "USE_BREWING_STANDS";
    public static final String BREW_DRINKABLE_POTIONS = "BREW_DRINKABLE_POTIONS";
    public static final String USE_DRINKABLE_POTIONS = "USE_DRINKABLE_POTIONS";
    public static final String TELEPORT_NETHER = "TELEPORT_NETHER";
    public static final String TELEPORT_END = "TELEPORT_END";
    public static final String EQUIP_ELYTRA = "EQUIP_ELYTRA";
    public static final String GLIDE_WITH_ELYTRA = "GLIDE_WITH_ELYTRA";
    public static final String BOOST_ELYTRA_WITH_FIREWORKS = "BOOST_ELYTRA_WITH_FIREWORKS";

    private static final Map<Material, Set<String>> PRODUCTION = buildProduction();
    private static final Map<Material, Set<String>> USE = buildUse();
    private static final Map<Material, Set<String>> BLOCK_STATIONS = buildBlockStations();

    private CapabilityPolicy() {}

    public static Set<String> requirementsForProduction(ItemStack result) {
        return result == null || result.getType() == Material.AIR ? Set.of()
            : PRODUCTION.getOrDefault(result.getType(), Set.of());
    }

    public static Set<String> requirementsForUse(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return Set.of();
        LinkedHashSet<String> requirements = new LinkedHashSet<>(USE.getOrDefault(item.getType(), Set.of()));
        if (!item.getEnchantments().isEmpty()) requirements.add(EQUIP_ENCHANTED_ITEMS);
        return Set.copyOf(requirements);
    }

    public static Set<String> requirementsForStation(InventoryType type) {
        return switch (type) {
            case ANVIL -> Set.of("USE_ANVILS");
            case BLAST_FURNACE -> Set.of("USE_BLAST_FURNACES");
            case HOPPER -> Set.of("USE_HOPPERS");
            case SMOKER -> Set.of("USE_SMOKER");
            case ENCHANTING -> Set.of("USE_ENCHANTING_TABLES");
            case GRINDSTONE -> Set.of("USE_GRINDSTONES_FOR_ENCHANTMENTS");
            case BREWING -> Set.of(USE_BREWING_STANDS);
            case CARTOGRAPHY -> Set.of("USE_CARTOGRAPHY_TABLE");
            case DISPENSER, DROPPER -> Set.of("USE_ADVANCED_REDSTONE");
            default -> Set.of();
        };
    }

    public static Set<String> requirementsForStation(Material block) {
        return BLOCK_STATIONS.getOrDefault(block, Set.of());
    }

    /** Capabilities needed to ride a vehicle that can be entered without holding its item. */
    public static Set<String> requirementsForVehicle(String entityType) {
        if (entityType == null) return Set.of();
        if (entityType.contains("CHEST_BOAT") || entityType.contains("CHEST_RAFT")) {
            return Set.of("USE_CHEST_BOATS");
        }
        if (entityType.endsWith("_BOAT") || entityType.endsWith("_RAFT")
            || entityType.equals("BOAT")) {
            return Set.of("USE_BOATS");
        }
        return switch (entityType) {
            case "MINECART", "CHEST_MINECART", "FURNACE_MINECART" -> Set.of("USE_BASIC_MINECARTS");
            case "HOPPER_MINECART" -> Set.of("USE_ADVANCED_MINECARTS");
            case "TNT_MINECART" -> Set.of("USE_TNT");
            default -> Set.of();
        };
    }

    public static Optional<String> firstMissing(Set<String> requirements, Set<String> capabilities) {
        return requirements.stream().filter(required -> !capabilities.contains(required)).findFirst();
    }

    private static Map<Material, Set<String>> buildProduction() {
        Map<Material, Set<String>> values = new LinkedHashMap<>();
        put(values, "CRAFT_SMOKER", "SMOKER");
        put(values, "CRAFT_COMPOSTER", "COMPOSTER");
        put(values, "CRAFT_BOATS", boatNames(false));
        put(values, "CRAFT_CHEST_BOATS", boatNames(true));
        put(values, "CRAFT_FISHING_RODS", "FISHING_ROD");
        equipment(values, "CRAFT_COPPER", "COPPER");
        put(values, "CRAFT_BOWS", "BOW");
        put(values, "CRAFT_NORMAL_ARROWS", "ARROW");
        put(values, "CRAFT_TARGET_BLOCKS", "TARGET");
        equipment(values, "CRAFT_IRON", "IRON");
        put(values, "CRAFT_SHIELDS", "SHIELD");
        put(values, "CRAFT_BUCKETS", "BUCKET");
        put(values, "CRAFT_SHEARS", "SHEARS");
        put(values, "CRAFT_FLINT_AND_STEEL", "FLINT_AND_STEEL");
        put(values, "CRAFT_NAVIGATION_ITEMS", "COMPASS", "CLOCK", "MAP");
        put(values, "CRAFT_CARTOGRAPHY_TABLE", "CARTOGRAPHY_TABLE");
        put(values, "CRAFT_CROSSBOWS", "CROSSBOW");
        put(values, "CRAFT_ANVILS", "ANVIL");
        put(values, "CRAFT_BLAST_FURNACES", "BLAST_FURNACE");
        put(values, "CRAFT_HOPPERS", "HOPPER");
        put(values, "CRAFT_PISTONS", "PISTON", "STICKY_PISTON");
        put(values, "CRAFT_BASIC_MINECARTS", "MINECART", "CHEST_MINECART", "FURNACE_MINECART");
        put(values, "CRAFT_NORMAL_RAILS", "RAIL");
        put(values, "CRAFT_ADVANCED_MINECARTS", "HOPPER_MINECART");
        put(values, "CRAFT_DETECTOR_RAILS", "DETECTOR_RAIL");
        put(values, "CRAFT_ACTIVATOR_RAILS", "ACTIVATOR_RAIL");
        put(values, Set.of("MECHANICAL_TRANSPORT", "REDSTONE_ENGINEERING"), "POWERED_RAIL");
        put(values, "CRAFT_ADVANCED_REDSTONE", "REPEATER", "COMPARATOR", "OBSERVER", "DISPENSER", "DROPPER", "REDSTONE_LAMP");
        put(values, "CRAFT_TNT", "TNT", "TNT_MINECART");
        put(values, Set.of("FLETCHING_MASTERY", "NETHER_PROGRESSION"), "SPECTRAL_ARROW");
        put(values, Set.of("FLETCHING_MASTERY", "ADVANCED_ALCHEMY"), "TIPPED_ARROW");
        equipment(values, "CRAFT_DIAMOND", "DIAMOND");
        put(values, "CRAFT_ENCHANTING_TABLES", "ENCHANTING_TABLE");
        put(values, "BREW_DRINKABLE_POTIONS", "POTION");
        put(values, "CRAFT_SPLASH_POTIONS", "SPLASH_POTION");
        put(values, "CRAFT_LINGERING_POTIONS", "LINGERING_POTION");
        equipment(values, "CRAFT_NETHERITE", "NETHERITE");
        return immutable(values);
    }

    private static Map<Material, Set<String>> buildUse() {
        Map<Material, Set<String>> values = new LinkedHashMap<>();
        put(values, "PLACE_SMOKER", "SMOKER");
        put(values, "PLACE_COMPOSTER", "COMPOSTER");
        put(values, "USE_BOATS", boatNames(false));
        put(values, "USE_CHEST_BOATS", boatNames(true));
        put(values, "USE_FISHING_RODS", "FISHING_ROD");
        equipment(values, "USE_COPPER", "COPPER");
        put(values, "USE_BOWS", "BOW");
        put(values, "USE_NORMAL_ARROWS", "ARROW");
        equipment(values, "USE_IRON", "IRON");
        put(values, "USE_SHIELDS", "SHIELD");
        put(values, "USE_BUCKETS", "BUCKET", "WATER_BUCKET", "LAVA_BUCKET", "POWDER_SNOW_BUCKET", "MILK_BUCKET");
        put(values, "USE_SHEARS", "SHEARS");
        put(values, "USE_FLINT_AND_STEEL", "FLINT_AND_STEEL");
        put(values, "USE_NAVIGATION_ITEMS", "COMPASS", "CLOCK", "MAP", "FILLED_MAP");
        put(values, "USE_CROSSBOWS", "CROSSBOW");
        put(values, "USE_ANVILS", "ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL");
        put(values, "USE_BLAST_FURNACES", "BLAST_FURNACE");
        put(values, "USE_HOPPERS", "HOPPER");
        put(values, "USE_PISTONS", "PISTON", "STICKY_PISTON");
        put(values, "USE_BASIC_MINECARTS", "MINECART", "CHEST_MINECART", "FURNACE_MINECART");
        put(values, "USE_NORMAL_RAILS", "RAIL");
        put(values, "USE_ADVANCED_MINECARTS", "HOPPER_MINECART");
        put(values, "USE_DETECTOR_RAILS", "DETECTOR_RAIL");
        put(values, "USE_ACTIVATOR_RAILS", "ACTIVATOR_RAIL");
        put(values, Set.of("MECHANICAL_TRANSPORT", "REDSTONE_ENGINEERING"), "POWERED_RAIL");
        put(values, "USE_ADVANCED_REDSTONE", "REPEATER", "COMPARATOR", "OBSERVER", "DISPENSER", "DROPPER", "REDSTONE_LAMP");
        put(values, "USE_TNT", "TNT", "TNT_MINECART");
        put(values, Set.of("FLETCHING_MASTERY", "NETHER_PROGRESSION"), "SPECTRAL_ARROW");
        put(values, Set.of("FLETCHING_MASTERY", "ADVANCED_ALCHEMY"), "TIPPED_ARROW");
        equipment(values, "USE_DIAMOND", "DIAMOND");
        put(values, USE_DRINKABLE_POTIONS, "POTION");
        put(values, "USE_SPLASH_POTIONS", "SPLASH_POTION");
        put(values, "USE_LINGERING_POTIONS", "LINGERING_POTION");
        put(values, EQUIP_ELYTRA, "ELYTRA");
        equipment(values, "USE_NETHERITE", "NETHERITE");
        return immutable(values);
    }

    private static Map<Material, Set<String>> buildBlockStations() {
        Map<Material, Set<String>> values = new LinkedHashMap<>();
        put(values, "USE_SMOKER", "SMOKER");
        put(values, "USE_COMPOSTER", "COMPOSTER");
        put(values, "USE_CARTOGRAPHY_TABLE", "CARTOGRAPHY_TABLE");
        put(values, "USE_ANVILS", "ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL");
        put(values, "USE_BLAST_FURNACES", "BLAST_FURNACE");
        put(values, "USE_HOPPERS", "HOPPER");
        put(values, "USE_ENCHANTING_TABLES", "ENCHANTING_TABLE");
        put(values, "USE_GRINDSTONES_FOR_ENCHANTMENTS", "GRINDSTONE");
        put(values, USE_BREWING_STANDS, "BREWING_STAND");
        put(values, "USE_ADVANCED_REDSTONE", "REPEATER", "COMPARATOR", "OBSERVER", "DISPENSER", "DROPPER",
            "REDSTONE_LAMP");
        put(values, "USE_TNT", "TNT");
        return immutable(values);
    }

    private static void equipment(Map<Material, Set<String>> values, String prefix, String material) {
        put(values, prefix + "_TOOLS", material + "_PICKAXE", material + "_AXE", material + "_SHOVEL", material + "_HOE");
        put(values, prefix + "_WEAPONS", material + "_SWORD");
        String armor = prefix.startsWith("CRAFT_") ? prefix + "_ARMOR" : "EQUIP_" + material + "_ARMOR";
        put(values, armor, material + "_HELMET", material + "_CHESTPLATE", material + "_LEGGINGS", material + "_BOOTS");
    }

    private static String[] boatNames(boolean chest) {
        String[] woods = {"OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "MANGROVE", "CHERRY", "PALE_OAK", "BAMBOO"};
        String[] names = new String[woods.length];
        for (int index = 0; index < woods.length; index++) names[index] = woods[index].equals("BAMBOO")
            ? "BAMBOO_" + (chest ? "CHEST_RAFT" : "RAFT")
            : woods[index] + (chest ? "_CHEST_BOAT" : "_BOAT");
        return names;
    }

    private static void put(Map<Material, Set<String>> values, String capability, String... names) {
        put(values, Set.of(capability), names);
    }

    private static void put(Map<Material, Set<String>> values, Set<String> capabilities, String... names) {
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material == null) continue;
            values.computeIfAbsent(material, ignored -> new LinkedHashSet<>()).addAll(capabilities);
        }
    }

    private static Map<Material, Set<String>> immutable(Map<Material, Set<String>> source) {
        Map<Material, Set<String>> values = new LinkedHashMap<>();
        source.forEach((material, requirements) -> values.put(material, Set.copyOf(requirements)));
        return Map.copyOf(values);
    }
}
