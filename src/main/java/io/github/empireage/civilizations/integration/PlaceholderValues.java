package io.github.empireage.civilizations.integration;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.config.TechnologyCatalog;
import io.github.empireage.civilizations.domain.Civilization;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.ResearchEntry;
import io.github.empireage.civilizations.domain.War;
import io.github.empireage.civilizations.domain.WarState;
import io.github.empireage.civilizations.service.territory.TerritoryRules;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Thread-safe resolver shared by PlaceholderAPI and direct consumers. */
public final class PlaceholderValues {
    private final Supplier<StateSnapshot> snapshots;
    private final Settings.Claims claimSettings;
    private final TechnologyCatalog technologies;
    private final Clock clock;

    public PlaceholderValues(StateCache cache, Settings settings, TechnologyCatalog technologies) {
        this(cache, settings, technologies, Clock.systemUTC());
    }

    public PlaceholderValues(StateCache cache, Settings settings, TechnologyCatalog technologies, Clock clock) {
        this(Objects.requireNonNull(cache, "cache")::snapshot, settings.claims(), technologies, clock);
    }

    PlaceholderValues(Supplier<StateSnapshot> snapshots, Settings.Claims claimSettings,
                      TechnologyCatalog technologies, Clock clock) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.claimSettings = Objects.requireNonNull(claimSettings, "claimSettings");
        this.technologies = Objects.requireNonNull(technologies, "technologies");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Returns null for an unknown placeholder, as PlaceholderAPI expects. */
    public String resolve(UUID playerId, String rawParameter) {
        if (rawParameter == null) return null;
        String parameter = rawParameter.toLowerCase(Locale.ROOT);
        StateSnapshot state = snapshots.get();
        Member member = playerId == null ? null : state.member(playerId);
        Civilization civilization = member == null ? null : state.civilization(member.civilizationId());
        return switch (parameter) {
            case "name" -> civilization == null ? "" : civilization.name();
            case "role" -> member == null ? "" : member.role().name();
            case "age" -> civilization == null ? "" : technologies.age(state.technologies(civilization.id()));
            case "claims" -> civilization == null ? "0" : Integer.toString(state.claims(civilization.id()).size());
            case "claim_limit" -> civilization == null ? "0" : Integer.toString(TerritoryRules.claimCapacity(
                state, civilization.id(), claimSettings, technologies));
            case "members" -> civilization == null ? "0" : Integer.toString(state.members(civilization.id()).size());
            case "research" -> research(state, civilization);
            case "research_remaining" -> researchRemaining(state, civilization);
            case "war_state" -> warState(state, civilization);
            case "war_enemy" -> warEnemy(state, civilization);
            case "war_remaining" -> warRemaining(state, civilization);
            default -> null;
        };
    }

    private String research(StateSnapshot state, Civilization civilization) {
        if (civilization == null) return "";
        return state.research(civilization.id()).stream()
            .sorted(Comparator.comparingInt(ResearchEntry::slot))
            .map(entry -> {
                var definition = technologies.get(entry.technologyKey());
                return definition == null ? entry.technologyKey() : definition.name();
            })
            .reduce((first, second) -> first + ", " + second).orElse("");
    }

    private String researchRemaining(StateSnapshot state, Civilization civilization) {
        if (civilization == null) return "0s";
        Instant due = state.research(civilization.id()).stream().map(ResearchEntry::completesAt)
            .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        return due == null ? "0s" : formatDuration(Duration.between(clock.instant(), due));
    }

    private String warState(StateSnapshot state, Civilization civilization) {
        War war = displayWar(state, civilization);
        return war == null ? "PEACE" : war.effectiveState(clock.instant()).name();
    }

    private String warEnemy(StateSnapshot state, Civilization civilization) {
        War war = displayWar(state, civilization);
        if (war == null) return "";
        Long opponentId = war.opponent(civilization.id());
        Civilization opponent = opponentId == null ? null : state.civilization(opponentId);
        return opponent == null ? "#" + opponentId : opponent.name();
    }

    private String warRemaining(StateSnapshot state, Civilization civilization) {
        War war = displayWar(state, civilization);
        if (war == null) return "0s";
        Instant now = clock.instant();
        WarState effective = war.effectiveState(now);
        Instant target = switch (effective) {
            case PENDING -> war.scheduledStart();
            case ACTIVE, RESOLVING -> war.scheduledEnd();
            case TRUCE -> war.truceUntil();
            case CANCELLED, COMPLETED -> null;
        };
        return target == null ? "0s" : formatDuration(Duration.between(now, target));
    }

    private War displayWar(StateSnapshot state, Civilization civilization) {
        if (civilization == null) return null;
        War current = state.warFor(civilization.id());
        if (current != null) return current;
        Instant now = clock.instant();
        return state.wars().values().stream()
            .filter(war -> war.state() == WarState.TRUCE)
            .filter(war -> war.opponent(civilization.id()) != null)
            .filter(war -> war.truceUntil() != null && now.isBefore(war.truceUntil()))
            .max(Comparator.comparing(War::truceUntil).thenComparingLong(War::id))
            .orElse(null);
    }

    static String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        long remainder = seconds % 60;
        StringBuilder value = new StringBuilder();
        if (days > 0) value.append(days).append('d');
        if (hours > 0) append(value, hours + "h");
        if (minutes > 0) append(value, minutes + "m");
        if (value.isEmpty() || (days == 0 && hours == 0 && minutes == 0)) append(value, remainder + "s");
        return value.toString();
    }

    private static void append(StringBuilder value, String part) {
        if (!value.isEmpty()) value.append(' ');
        value.append(part);
    }
}
