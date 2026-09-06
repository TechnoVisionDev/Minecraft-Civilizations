package io.github.empireage.civilizations.config;

import io.github.empireage.civilizations.domain.ResourceKey;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ResourceCatalog(
    int recipeVersion,
    boolean creativeDeposits,
    int higherTierRatio,
    Map<ResourceKey, Tier> tiers,
    Map<String, BaseRecipe> baseRecipes,
    List<ClaimCostBand> claimCostBands
) {
    public record Tier(ResourceKey key, Material material, String displayName, int modelData) {}
    public record BaseRecipe(String family, Map<Material, Integer> ingredients, Set<Material> acceptedAlternatives) {}
    public record ClaimCostBand(int maximumClaims, Map<ResourceKey, Long> costs) {}

    public Tier require(ResourceKey key) {
        Tier tier = tiers.get(key);
        if (tier == null) throw new IllegalArgumentException("Unknown civic resource " + key.serialized());
        return tier;
    }

    public Map<ResourceKey, Long> claimCost(int resultingClaimCount) {
        return claimCostBands.stream()
            .filter(band -> resultingClaimCount <= band.maximumClaims())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No claim cost band covers claim " + resultingClaimCount))
            .costs();
    }

    public static ResourceCatalog load(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int recipeVersion = yaml.getInt("recipe-version", 1);
        int ratio = yaml.getInt("higher-tier-ratio", 9);
        if (recipeVersion < 1 || ratio < 2) throw new IllegalArgumentException("Invalid resource recipe version or tier ratio");
        ConfigurationSection families = requireSection(yaml, "families");
        Map<ResourceKey, Tier> tiers = new LinkedHashMap<>();
        Map<String, BaseRecipe> recipes = new LinkedHashMap<>();
        for (String family : families.getKeys(false)) {
            ConfigurationSection familySection = requireSection(families, family);
            ConfigurationSection tierSection = requireSection(familySection, "tiers");
            for (String tierKey : tierSection.getKeys(false)) {
                int tierNumber = Integer.parseInt(tierKey);
                ConfigurationSection data = requireSection(tierSection, tierKey);
                ResourceKey key = new ResourceKey(family, tierNumber);
                Material material = material(data.getString("material"), "families." + family + ".tiers." + tierKey);
                tiers.put(key, new Tier(key, material, data.getString("name", family + " " + tierNumber), data.getInt("model-data", 0)));
            }
            ConfigurationSection recipe = familySection.getConfigurationSection("base-recipe");
            Map<Material, Integer> ingredients = new LinkedHashMap<>();
            if (recipe != null) {
                ConfigurationSection ingredientSection = requireSection(recipe, "ingredients");
                for (String materialName : ingredientSection.getKeys(false)) {
                    ingredients.put(material(materialName, "base recipe " + family), ingredientSection.getInt(materialName));
                }
            }
            Set<Material> alternatives = new LinkedHashSet<>();
            for (String value : familySection.getStringList("accepted-logs")) alternatives.add(material(value, "accepted-logs"));
            recipes.put(family, new BaseRecipe(family, Map.copyOf(ingredients), Set.copyOf(alternatives)));
        }
        List<ClaimCostBand> bands = new ArrayList<>();
        for (Map<?, ?> raw : yaml.getMapList("claim-cost-bands")) {
            int max = ((Number) raw.get("max-claims")).intValue();
            Object rawCosts = raw.get("costs");
            if (!(rawCosts instanceof Map<?, ?> values)) throw new IllegalArgumentException("Claim band costs must be a map");
            Map<ResourceKey, Long> costs = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                costs.put(ResourceKey.parse(String.valueOf(entry.getKey())), ((Number) entry.getValue()).longValue());
            }
            bands.add(new ClaimCostBand(max, Map.copyOf(costs)));
        }
        bands.sort(Comparator.comparingInt(ClaimCostBand::maximumClaims));
        if (bands.isEmpty()) throw new IllegalArgumentException("At least one claim cost band is required");
        return new ResourceCatalog(recipeVersion, yaml.getBoolean("creative-deposits", false), ratio,
            Map.copyOf(tiers), Map.copyOf(recipes), List.copyOf(bands));
    }

    private static Material material(String input, String path) {
        Material material = Material.matchMaterial(input == null ? "" : input);
        if (material == null) throw new IllegalArgumentException("Unknown material at " + path + ": " + input);
        return material;
    }

    private static ConfigurationSection requireSection(ConfigurationSection parent, String path) {
        ConfigurationSection result = parent.getConfigurationSection(path);
        if (result == null) throw new IllegalArgumentException("Missing configuration section " + path);
        return result;
    }
}
