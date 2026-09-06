package io.github.empireage.civilizations.service.progression;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.config.TechnologyCatalog;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.TechnologyDefinition;
import io.github.empireage.civilizations.domain.TechnologyMode;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/** Read-only cache-backed technology, capability, and numeric-modifier facade. */
public final class TechnologyAccess {
    public static final String BYPASS_PERMISSION = "civilizations.bypass.technology";

    private final StateCache cache;
    private final TechnologyCatalog catalog;
    private final Settings.Technology settings;

    public TechnologyAccess(StateCache cache, TechnologyCatalog catalog, Settings.Technology settings) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public TechnologyMode mode() {
        return settings.enforcement();
    }

    public boolean allowsProduction(Player player, Set<String> requirements) {
        return !productionGated(mode()) || permitted(player, requirements);
    }

    public boolean allowsUse(Player player, Set<String> requirements) {
        return !useGated(mode()) || permitted(player, requirements);
    }

    public boolean permitted(Player player, Set<String> requirements) {
        if (requirements.isEmpty() || mode() == TechnologyMode.DISABLED || player.hasPermission(BYPASS_PERMISSION)) return true;
        if (!cache.ready()) return false;
        OptionalLong civilization = civilizationId(player.getUniqueId());
        return civilization.isPresent() && capabilities(civilization.getAsLong()).containsAll(requirements);
    }

    public boolean hasCapability(long civilizationId, String capability) {
        return capabilities(civilizationId).contains(capability);
    }

    public boolean hasTechnology(long civilizationId, String technologyKey) {
        return cache.snapshot().technologies(civilizationId).contains(technologyKey.toLowerCase(java.util.Locale.ROOT));
    }

    public Set<String> capabilities(long civilizationId) {
        LinkedHashSet<String> capabilities = new LinkedHashSet<>();
        for (String key : cache.snapshot().technologies(civilizationId)) {
            TechnologyDefinition definition = catalog.get(key);
            if (definition != null) capabilities.addAll(definition.capabilities());
        }
        return Set.copyOf(capabilities);
    }

    public int modifier(long civilizationId, String modifierKey) {
        long total = 0;
        for (String key : cache.snapshot().technologies(civilizationId)) {
            TechnologyDefinition definition = catalog.get(key);
            if (definition != null) total += definition.modifiers().getOrDefault(modifierKey, 0);
        }
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, total));
    }

    public int claimCapacityBonus(long civilizationId) { return modifier(civilizationId, "claim-capacity"); }
    public int plotCapacityBonus(long civilizationId) { return modifier(civilizationId, "plot-capacity"); }
    public int advisorCapacityBonus(long civilizationId) { return modifier(civilizationId, "advisor-capacity"); }
    public int researchQueueCapacity(long civilizationId) { return Math.max(1, 1 + modifier(civilizationId, "research-queues")); }

    public OptionalLong civilizationId(UUID playerId) {
        Member member = cache.snapshot().member(playerId);
        return member == null ? OptionalLong.empty() : OptionalLong.of(member.civilizationId());
    }

    public static boolean productionGated(TechnologyMode mode) {
        return mode == TechnologyMode.STRICT || mode == TechnologyMode.CRAFT_ONLY;
    }

    public static boolean useGated(TechnologyMode mode) {
        return mode == TechnologyMode.STRICT;
    }
}
