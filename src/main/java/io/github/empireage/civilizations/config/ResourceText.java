package io.github.empireage.civilizations.config;

import io.github.empireage.civilizations.domain.ResourceKey;
import org.bukkit.ChatColor;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Player-facing names for civic resources; persistent keys must never leak through this formatter. */
public final class ResourceText {
    private ResourceText() {}

    public static ChatColor tierColor(int tier) {
        return switch (tier) {
            case 1 -> ChatColor.GREEN;
            case 2 -> ChatColor.AQUA;
            case 3 -> ChatColor.LIGHT_PURPLE;
            default -> throw new IllegalArgumentException("Tier must be 1-3");
        };
    }

    /** One name format shared by physical tokens and every civic-item preview. */
    public static String itemName(ResourceCatalog catalog, ResourceKey key) {
        return tierColor(key.tier()) + name(catalog, key);
    }

    public static String name(ResourceCatalog catalog, ResourceKey key) {
        Objects.requireNonNull(key, "key");
        ResourceCatalog.Tier tier = catalog == null ? null : catalog.tiers().get(key);
        if (tier != null) {
            return ChatColor.stripColor(Messages.color(tier.displayName()));
        }
        return title(key.family()) + " Tier " + key.tier();
    }

    public static String cost(ResourceCatalog catalog, Map<ResourceKey, Long> values) {
        if (values == null || values.isEmpty()) return "no civic materials";
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getValue() + " " + name(catalog, entry.getKey()))
            .collect(Collectors.joining(", "));
    }

    private static String title(String value) {
        String[] words = value.replace('-', '_').split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase());
        }
        return result.isEmpty() ? "Civic Resource" : result.toString();
    }
}
