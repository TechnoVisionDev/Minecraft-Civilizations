package io.github.empireage.civilizations.config;

import io.github.empireage.civilizations.domain.ResourceKey;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ResourceTextTest {
    private final ResourceCatalog resources = ResourceCatalog.load(new File("src/main/resources/resources.yml"));

    @Test
    void formatsCostsWithTierItemNamesAndNeverPersistentCodes() {
        Map<ResourceKey, Long> cost = new LinkedHashMap<>();
        cost.put(ResourceKey.parse("masonry:1"), 4L);
        cost.put(ResourceKey.parse("timber:1"), 2L);

        String rendered = ResourceText.cost(resources, cost);

        assertEquals("4 Heavy Cobblestone, 2 Bound Timber", rendered);
        assertFalse(rendered.contains("masonry:1"));
        assertFalse(rendered.contains("timber:1"));
        assertFalse(rendered.contains("{"));
    }

    @Test
    void stripsConfiguredColorCodesFromNames() {
        assertEquals("Tempered Mechanisms", ResourceText.name(resources, ResourceKey.parse("metalwork:2")));
    }

    @Test
    void tierColorsOverrideEmbeddedFormattingWithoutChangingTheName() {
        ChatColor[] expected = {ChatColor.GREEN, ChatColor.AQUA, ChatColor.LIGHT_PURPLE};
        for (int tier = 1; tier <= 3; tier++) {
            ResourceKey key = new ResourceKey("custom", tier);
            ResourceCatalog custom = new ResourceCatalog(1, false, 9,
                Map.of(key, new ResourceCatalog.Tier(key, Material.PAPER, "&cCustom &lResource", 0)), Map.of(), java.util.List.of());
            assertEquals(expected[tier - 1] + "Custom Resource", ResourceText.itemName(custom, key));
        }
    }
}
