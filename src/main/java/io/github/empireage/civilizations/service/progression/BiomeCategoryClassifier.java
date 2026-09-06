package io.github.empireage.civilizations.service.progression;

import org.bukkit.block.Biome;

import java.util.Locale;

/** Stable broad categories for work-order matching across Minecraft biome additions. */
public final class BiomeCategoryClassifier {
    private BiomeCategoryClassifier() {}

    public static String classify(Biome biome) {
        String name = biome.name().toUpperCase(Locale.ROOT);
        if (contains(name, "OCEAN", "BEACH", "RIVER")) return "OCEAN";
        if (contains(name, "DESERT", "BADLANDS")) return "DESERT";
        if (contains(name, "JUNGLE", "BAMBOO")) return "JUNGLE";
        if (contains(name, "TAIGA", "GROVE")) return "TAIGA";
        if (contains(name, "SAVANNA")) return "SAVANNA";
        if (contains(name, "MOUNTAIN", "PEAK", "SLOPE", "HILLS", "CLIFF")) return "MOUNTAIN";
        if (contains(name, "FOREST", "WOODS", "CHERRY", "DARK_FOREST", "PALE_GARDEN")) return "FOREST";
        return name;
    }

    private static boolean contains(String value, String... fragments) {
        for (String fragment : fragments) if (value.contains(fragment)) return true;
        return false;
    }
}
