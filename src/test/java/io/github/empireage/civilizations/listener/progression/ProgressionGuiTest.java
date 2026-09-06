package io.github.empireage.civilizations.listener.progression;

import io.github.empireage.civilizations.config.ResourceCatalog;
import io.github.empireage.civilizations.config.TechnologyCatalog;
import io.github.empireage.civilizations.domain.ResourceKey;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
