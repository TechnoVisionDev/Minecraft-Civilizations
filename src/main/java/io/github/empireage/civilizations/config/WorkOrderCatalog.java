package io.github.empireage.civilizations.config;

import io.github.empireage.civilizations.domain.ResourceKey;
import io.github.empireage.civilizations.util.DurationParser;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Configuration for persistent civilization supply orders. */
public record WorkOrderCatalog(Map<String, Template> templates, Duration cooldown) {
    public enum Category { BASIC, INDUSTRIAL, STRATEGIC }

    public record Template(String key, String name, Category category, Set<Material> materials,
                           ResourceKey civicResource, Set<String> requiredTechnologies,
                           long target, int reward, int weight) {
        public boolean civic() { return civicResource != null; }
    }

    public WorkOrderCatalog {
        templates = Map.copyOf(templates);
        if (cooldown == null || cooldown.isNegative()) throw new IllegalArgumentException("Work-order cooldown cannot be negative");
    }

    public Template require(String key) {
        Template template = templates.get(key);
        if (template == null) throw new IllegalArgumentException("Unknown work-order template " + key);
        return template;
    }

    public List<Template> category(Category category) {
        return templates.values().stream().filter(template -> template.category() == category).toList();
    }

    public static WorkOrderCatalog load(File file) {
        return load(file, null);
    }

    public static WorkOrderCatalog load(File file, ResourceCatalog resources) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("templates");
        if (section == null) throw new IllegalArgumentException("workorders.yml has no templates section");
        Map<String, Template> templates = new LinkedHashMap<>();
        Map<Category, Integer> categoryCounts = new EnumMap<>(Category.class);
        boolean legacyCategoryDetected = false;
        for (String rawKey : section.getKeys(false)) {
            String key = rawKey.toLowerCase(Locale.ROOT);
            ConfigurationSection data = section.getConfigurationSection(rawKey);
            if (data == null) throw new IllegalArgumentException("Invalid work-order template " + key);
            ParsedCategory parsedCategory = parseCategory(key, data);
            Category category = parsedCategory.category();
            legacyCategoryDetected |= parsedCategory.inferred();
            Set<Material> materials = new LinkedHashSet<>();
            for (String materialName : data.getStringList("materials")) {
                Material material = Material.matchMaterial(materialName);
                if (material == null) throw new IllegalArgumentException("Unknown work-order material " + materialName);
                materials.add(material);
            }
            String civicValue = data.getString("civic-resource");
            ResourceKey civic = civicValue == null || civicValue.isBlank() ? null : ResourceKey.parse(civicValue);
            if (civic != null && resources != null) resources.require(civic);
            if ((civic == null && materials.isEmpty()) || (civic != null && !materials.isEmpty())) {
                throw new IllegalArgumentException(key + " must define exactly one of materials or civic-resource");
            }
            long target = data.getLong("target");
            int reward = data.getInt("reward");
            int weight = data.getInt("weight", 1);
            if (target < 1 || reward < 1 || weight < 1) throw new IllegalArgumentException(
                key + " target, reward, and weight must be positive");
            Set<String> requirements = data.getStringList("requires-technologies").stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
            templates.put(key, new Template(key, data.getString("name", key), category, Set.copyOf(materials), civic,
                requirements, target, reward, weight));
            categoryCounts.merge(category, 1, Integer::sum);
        }
        for (Category category : Category.values()) if (categoryCounts.getOrDefault(category, 0) < 2) {
            if (legacyCategoryDetected) throw new LegacyCatalogException(
                "The legacy workorders.yml cannot provide at least two " + category + " templates");
            throw new IllegalArgumentException("Work-order category " + category + " requires at least two templates");
        }
        return new WorkOrderCatalog(templates, DurationParser.parse(yaml.getString("cooldown", "24h")));
    }

    private static ParsedCategory parseCategory(String key, ConfigurationSection data) {
        String configured = firstCategoryValue(data);
        if (configured == null) {
            for (Category category : Category.values()) {
                String prefix = category.name().toLowerCase(Locale.ROOT);
                if (key.equals(prefix) || key.startsWith(prefix + "_") || key.startsWith(prefix + "-")
                    || key.startsWith(prefix + ".")) return new ParsedCategory(category, true);
            }
            throw new LegacyCatalogException("Work-order template " + key
                + " has no category and its legacy key does not identify one");
        }
        String normalized = configured.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        normalized = switch (normalized) {
            case "FOUNDATIONAL", "FOUNDATION" -> "BASIC";
            case "INDUSTRY" -> "INDUSTRIAL";
            case "ADVANCED", "MILITARY" -> "STRATEGIC";
            default -> normalized;
        };
        try {
            return new ParsedCategory(Category.valueOf(normalized), false);
        } catch (IllegalArgumentException invalid) {
            throw new LegacyCatalogException("Work-order template " + key + " uses obsolete or unknown category '"
                + configured + "'; the current categories are BASIC, INDUSTRIAL, and STRATEGIC", invalid);
        }
    }

    private static String firstCategoryValue(ConfigurationSection data) {
        for (String path : List.of("category", "pool", "type")) {
            String value = data.getString(path);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private record ParsedCategory(Category category, boolean inferred) {}

    static final class LegacyCatalogException extends IllegalArgumentException {
        LegacyCatalogException(String message) {
            super(message);
        }

        LegacyCatalogException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
