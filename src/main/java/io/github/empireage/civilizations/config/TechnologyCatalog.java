package io.github.empireage.civilizations.config;

import io.github.empireage.civilizations.domain.ResourceKey;
import io.github.empireage.civilizations.domain.TechnologyDefinition;
import io.github.empireage.civilizations.util.DurationParser;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record TechnologyCatalog(Map<String, TechnologyDefinition> technologies) {
    public TechnologyDefinition get(String key) {
        return technologies.get(key.toLowerCase(Locale.ROOT));
    }

    public TechnologyDefinition require(String key) {
        TechnologyDefinition definition = get(key);
        if (definition == null) throw new IllegalArgumentException("Unknown technology " + key);
        return definition;
    }

    public String age(Set<String> unlocked) {
        if (unlocked.contains("imperial_administration")) return "Imperial Age";
        long highCount = Set.of("enchanting", "alchemy", "advanced_alchemy", "administration_iii").stream()
            .filter(unlocked::contains).count();
        if (unlocked.contains("diamondworking") && highCount >= 2) return "High Age";
        if (unlocked.contains("ironworking") && unlocked.contains("civic_planning")) return "Iron Age";
        return "Settlement";
    }

    public static TechnologyCatalog load(File file, ResourceCatalog resources) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("technologies");
        if (section == null) throw new IllegalArgumentException("technologies.yml has no technologies section");
        Map<String, TechnologyDefinition> definitions = new LinkedHashMap<>();
        for (String rawKey : section.getKeys(false)) {
            String key = rawKey.toLowerCase(Locale.ROOT);
            ConfigurationSection value = section.getConfigurationSection(rawKey);
            if (value == null) throw new IllegalArgumentException("Technology " + key + " is not a section");
            Map<ResourceKey, Long> costs = new LinkedHashMap<>();
            ConfigurationSection costSection = value.getConfigurationSection("costs");
            if (costSection != null) for (String costKey : costSection.getKeys(false)) {
                ResourceKey parsed = ResourceKey.parse(costKey);
                resources.require(parsed);
                long amount = costSection.getLong(costKey);
                if (amount < 0) throw new IllegalArgumentException("Negative cost in " + key);
                costs.put(parsed, amount);
            }
            Map<String, Integer> modifiers = new LinkedHashMap<>();
            ConfigurationSection modifierSection = value.getConfigurationSection("modifiers");
            if (modifierSection != null) for (String modifier : modifierSection.getKeys(false)) {
                if (!modifier.equals("war-objectives")) modifiers.put(modifier, modifierSection.getInt(modifier));
            }
            definitions.put(key, new TechnologyDefinition(
                key,
                value.getString("name", key),
                value.getString("era", "SETTLEMENT"),
                value.getStringList("prerequisites").stream().map(s -> s.toLowerCase(Locale.ROOT)).toList(),
                value.getLong("knowledge"),
                DurationParser.parse(value.getString("duration", "1h")),
                Map.copyOf(costs),
                value.getStringList("capabilities").stream()
                    .filter(capability -> !capability.startsWith("EXPAND_WAR_OBJECTIVES_"))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                Map.copyOf(modifiers)
            ));
        }
        TechnologyCatalog catalog = new TechnologyCatalog(Map.copyOf(definitions));
        catalog.validateGraph();
        return catalog;
    }

    private void validateGraph() {
        for (TechnologyDefinition definition : technologies.values()) {
            for (String prerequisite : definition.prerequisites()) {
                if (!technologies.containsKey(prerequisite)) throw new IllegalArgumentException(
                    definition.key() + " references missing prerequisite " + prerequisite);
            }
        }
        Set<String> completed = new HashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String key : technologies.keySet()) visit(key, visiting, completed);
    }

    private void visit(String key, Set<String> visiting, Set<String> completed) {
        if (completed.contains(key)) return;
        if (!visiting.add(key)) throw new IllegalArgumentException("Technology prerequisite cycle: " + visiting + " -> " + key);
        for (String prerequisite : technologies.get(key).prerequisites()) visit(prerequisite, visiting, completed);
        visiting.remove(key);
        completed.add(key);
    }
}
