package io.github.empireage.civilizations.domain;

import java.util.Locale;
import java.util.Objects;

public record ResourceKey(String family, int tier) implements Comparable<ResourceKey> {
    public ResourceKey {
        Objects.requireNonNull(family, "family");
        family = family.toLowerCase(Locale.ROOT);
        if (!family.matches("[a-z0-9_]{2,48}")) throw new IllegalArgumentException("Invalid resource family: " + family);
        if (tier < 1 || tier > 3) throw new IllegalArgumentException("Tier must be 1-3");
    }

    public static ResourceKey parse(String input) {
        String[] parts = input.split(":", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Resource key must be family:tier");
        return new ResourceKey(parts[0], Integer.parseInt(parts[1]));
    }

    public String serialized() {
        return family + ":" + tier;
    }

    @Override
    public int compareTo(ResourceKey other) {
        int familyOrder = family.compareTo(other.family);
        return familyOrder != 0 ? familyOrder : Integer.compare(tier, other.tier);
    }
}
