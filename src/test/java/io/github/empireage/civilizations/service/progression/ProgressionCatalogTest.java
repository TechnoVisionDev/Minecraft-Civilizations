package io.github.empireage.civilizations.service.progression;

import io.github.empireage.civilizations.config.ResourceCatalog;
import io.github.empireage.civilizations.config.TechnologyCatalog;
import io.github.empireage.civilizations.config.WorkOrderCatalog;
import io.github.empireage.civilizations.domain.ResourceKey;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressionCatalogTest {
    @Test
    void packagedCatalogsFormACompleteProgressionGraph() {
        ResourceCatalog resources = ResourceCatalog.load(new File("src/main/resources/resources.yml"));
        TechnologyCatalog technologies = TechnologyCatalog.load(new File("src/main/resources/technologies.yml"), resources);
        WorkOrderCatalog workOrders = WorkOrderCatalog.load(new File("src/main/resources/workorders.yml"));

        assertEquals(15, resources.tiers().size());
        assertEquals(26, technologies.technologies().size());
        assertEquals(9, technologies.technologies().values().stream()
            .filter(technology -> technology.era().equals("SETTLEMENT")).count());
        assertEquals(8, technologies.technologies().values().stream()
            .filter(technology -> technology.era().equals("IRON_AGE")).count());
        assertEquals(5, technologies.technologies().values().stream()
            .filter(technology -> technology.era().equals("HIGH_AGE")).count());
        assertEquals(4, technologies.technologies().values().stream()
            .filter(technology -> technology.era().equals("IMPERIAL")).count());
        Map<String, Long> expectedKnowledge = new LinkedHashMap<>();
        expectedKnowledge.put("agriculture", 9L);
        expectedKnowledge.put("animal_husbandry", 10L);
        expectedKnowledge.put("seafaring", 10L);
        expectedKnowledge.put("copperworking", 10L);
        expectedKnowledge.put("archery", 12L);
        expectedKnowledge.put("civic_planning", 13L);
        expectedKnowledge.put("horseback_riding", 14L);
        expectedKnowledge.put("ironworking", 15L);
        expectedKnowledge.put("scholarship", 15L);
        expectedKnowledge.put("navigation", 23L);
        expectedKnowledge.put("fletching", 23L);
        expectedKnowledge.put("mechanical_transport", 25L);
        expectedKnowledge.put("engineering", 28L);
        expectedKnowledge.put("fortification", 30L);
        expectedKnowledge.put("administration_ii", 31L);
        expectedKnowledge.put("redstone_engineering", 35L);
        expectedKnowledge.put("nether_expedition", 35L);
        expectedKnowledge.put("diamondworking", 55L);
        expectedKnowledge.put("enchanting", 53L);
        expectedKnowledge.put("alchemy", 53L);
        expectedKnowledge.put("administration_iii", 60L);
        expectedKnowledge.put("advanced_alchemy", 65L);
        expectedKnowledge.put("end_expedition", 88L);
        expectedKnowledge.put("aeronautics", 90L);
        expectedKnowledge.put("netherite_smithing", 95L);
        expectedKnowledge.put("imperial_administration", 105L);
        assertEquals(expectedKnowledge.keySet(), technologies.technologies().keySet());
        expectedKnowledge.forEach((key, knowledge) -> assertEquals(knowledge.longValue(),
            technologies.require(key).knowledgeCost(), key));
        assertEquals(1002, technologies.technologies().values().stream()
            .mapToLong(technology -> technology.knowledgeCost()).sum());
        assertFalse(technologies.technologies().containsKey("siegecraft"));
        assertFalse(technologies.technologies().containsKey("commerce"));
        assertFalse(workOrders.templates().isEmpty());
        assertEquals(5, workOrders.category(WorkOrderCatalog.Category.BASIC).size());
        assertEquals(6, workOrders.category(WorkOrderCatalog.Category.INDUSTRIAL).size());
        assertEquals(6, workOrders.category(WorkOrderCatalog.Category.STRATEGIC).size());
        assertEquals(1, workOrders.require("strategic_sovereign_matrix").target());
        assertEquals(5, workOrders.require("strategic_sovereign_matrix").reward());
        assertEquals(java.time.Duration.ofHours(24), workOrders.cooldown());
        technologies.technologies().values().forEach(technology ->
            technology.materialCosts().keySet().forEach(resources::require));
        assertEquals("Settlement", technologies.age(Set.of()));
        assertEquals("Iron Age", technologies.age(Set.of("ironworking", "civic_planning")));
        assertEquals("High Age", technologies.age(Set.of("diamondworking", "enchanting", "alchemy")));
        assertEquals("Imperial Age", technologies.age(Set.of("imperial_administration")));
        assertEquals(List.of("copperworking"), technologies.require("ironworking").prerequisites());
        assertEquals(List.of("seafaring", "scholarship"), technologies.require("navigation").prerequisites());
        assertEquals(List.of("archery", "ironworking"), technologies.require("fletching").prerequisites());
        assertEquals(List.of("engineering"), technologies.require("mechanical_transport").prerequisites());
        assertEquals(List.of("ironworking"), technologies.require("engineering").prerequisites());
        assertEquals(List.of("civic_planning", "engineering"), technologies.require("fortification").prerequisites());
        assertEquals(List.of("civic_planning"), technologies.require("administration_ii").prerequisites());
        assertEquals(List.of("engineering", "scholarship"), technologies.require("redstone_engineering").prerequisites());
        assertEquals(List.of("ironworking", "scholarship"), technologies.require("nether_expedition").prerequisites());
        assertEquals(List.of("engineering", "scholarship"), technologies.require("diamondworking").prerequisites());
        assertEquals(List.of("diamondworking", "scholarship"), technologies.require("enchanting").prerequisites());
        assertEquals(List.of("nether_expedition", "scholarship"), technologies.require("alchemy").prerequisites());
        assertEquals(List.of("administration_ii", "fortification"), technologies.require("administration_iii").prerequisites());
        assertEquals(List.of("alchemy", "scholarship"), technologies.require("advanced_alchemy").prerequisites());
        assertEquals(List.of("diamondworking", "enchanting"), technologies.require("end_expedition").prerequisites());
        assertEquals(List.of("end_expedition", "engineering"), technologies.require("aeronautics").prerequisites());
        assertEquals(List.of("diamondworking", "nether_expedition"), technologies.require("netherite_smithing").prerequisites());
        assertEquals(List.of("administration_iii", "fortification"),
            technologies.require("imperial_administration").prerequisites());
        assertTrue(technologies.require("nether_expedition").capabilities().contains(CapabilityPolicy.TELEPORT_NETHER));
        assertFalse(technologies.require("nether_expedition").capabilities().contains("USE_NETHER_PORTALS"));
        assertTrue(technologies.require("end_expedition").capabilities().contains(CapabilityPolicy.TELEPORT_END));
        assertFalse(technologies.require("end_expedition").capabilities().contains("USE_END_PORTALS"));
        assertEquals(Map.of("claim-capacity", 8), technologies.require("civic_planning").modifiers());
        assertEquals(Map.of("claim-capacity", 12), technologies.require("fortification").modifiers());
        assertEquals(Map.of("claim-capacity", 8, "plot-capacity", 1, "advisor-capacity", 2),
            technologies.require("administration_ii").modifiers());
        assertEquals(Map.of("claim-capacity", 20, "plot-capacity", 2),
            technologies.require("administration_iii").modifiers());
        assertEquals(Map.of("claim-capacity", 32, "plot-capacity", 3,
            "research-queues", 1), technologies.require("imperial_administration").modifiers());
        assertTrue(technologies.technologies().values().stream().flatMap(technology -> technology.capabilities().stream())
            .noneMatch("ADVANCED_FARMING"::equals));
        assertTrue(technologies.require("imperial_administration").capabilities().contains("EXPAND_RESEARCH_QUEUES_II"));

        assertEquals(Map.of(
            "basic_heavy_cobblestone", 32L,
            "basic_bound_timber", 24L,
            "basic_forged_components", 12L,
            "basic_research_folios", 12L,
            "basic_arcane_matrices", 8L
        ), targets(workOrders, WorkOrderCatalog.Category.BASIC));
        assertEquals(Map.of(
            "industrial_reinforced_masonry", 5L,
            "industrial_engineered_timber", 4L,
            "industrial_tempered_mechanisms", 4L,
            "industrial_bound_archives", 4L,
            "industrial_masonry_reserve", 64L,
            "industrial_timber_reserve", 48L
        ), targets(workOrders, WorkOrderCatalog.Category.INDUSTRIAL));
        assertEquals(Map.of(
            "strategic_monumental_masonry", 1L,
            "strategic_great_beams", 1L,
            "strategic_imperial_mechanisms", 1L,
            "strategic_grand_codex", 1L,
            "strategic_resonant_matrices", 4L,
            "strategic_sovereign_matrix", 1L
        ), targets(workOrders, WorkOrderCatalog.Category.STRATEGIC));
        assertTrue(workOrders.templates().values().stream().allMatch(WorkOrderCatalog.Template::civic));
        workOrders.category(WorkOrderCatalog.Category.BASIC).forEach(order -> assertEquals(2, order.reward()));
        workOrders.category(WorkOrderCatalog.Category.INDUSTRIAL).forEach(order -> assertEquals(3, order.reward()));
        workOrders.category(WorkOrderCatalog.Category.STRATEGIC).forEach(order ->
            assertEquals(order.key().equals("strategic_sovereign_matrix") ? 5 : 4, order.reward()));
    }

    @Test
    void claimBandsAndCompressionRatioAreExactAtBoundaries() {
        ResourceCatalog resources = ResourceCatalog.load(new File("src/main/resources/resources.yml"));

        assertEquals(9, resources.higherTierRatio());
        assertEquals(Map.of(ResourceKey.parse("masonry:1"), 2L, ResourceKey.parse("timber:1"), 1L),
            resources.claimCost(8));
        assertEquals(1L, resources.claimCost(9).get(ResourceKey.parse("masonry:2")));
        assertTrue(resources.claimCost(128).containsKey(ResourceKey.parse("masonry:3")));
    }

    private static Map<String, Long> targets(WorkOrderCatalog catalog, WorkOrderCatalog.Category category) {
        Map<String, Long> targets = new LinkedHashMap<>();
        catalog.category(category).forEach(order -> targets.put(order.key(), order.target()));
        return Map.copyOf(targets);
    }
}
