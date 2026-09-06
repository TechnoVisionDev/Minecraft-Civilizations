package io.github.empireage.civilizations.cache;

import io.github.empireage.civilizations.domain.Claim;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.Civilization;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.ResearchEntry;
import io.github.empireage.civilizations.domain.Role;
import io.github.empireage.civilizations.domain.War;
import io.github.empireage.civilizations.domain.WarObjective;
import io.github.empireage.civilizations.util.NameNormalizer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record StateSnapshot(
    Map<Long, Civilization> civilizations,
    Map<String, Long> civilizationNames,
    Map<UUID, Member> memberships,
    Map<Long, List<Member>> membersByCivilization,
    Map<ChunkKey, Claim> claims,
    Map<Long, List<Claim>> claimsByCivilization,
    Map<Long, Set<String>> technologies,
    Map<Long, List<ResearchEntry>> research,
    Map<Long, War> wars,
    Map<Long, War> warsByCivilization,
    Map<Long, List<WarObjective>> objectivesByWar,
    Instant loadedAt
) {
    public StateSnapshot {
        civilizations = Map.copyOf(civilizations);
        civilizationNames = Map.copyOf(civilizationNames);
        memberships = Map.copyOf(memberships);
        membersByCivilization = immutableLists(membersByCivilization);
        claims = Map.copyOf(claims);
        claimsByCivilization = immutableLists(claimsByCivilization);
        technologies = immutableSets(technologies);
        research = immutableLists(research);
        wars = Map.copyOf(wars);
        warsByCivilization = Map.copyOf(warsByCivilization);
        objectivesByWar = immutableLists(objectivesByWar);
    }

    public static StateSnapshot empty() {
        return new StateSnapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
            Map.of(), Map.of(), Map.of(), Instant.EPOCH);
    }

    public Civilization civilization(long id) {
        return civilizations.get(id);
    }

    public Civilization civilization(String name) {
        Long id = civilizationNames.get(NameNormalizer.normalize(name));
        return id == null ? null : civilizations.get(id);
    }

    public Member member(UUID playerId) {
        return memberships.get(playerId);
    }

    public Claim claim(ChunkKey key) {
        return claims.get(key);
    }

    public List<Member> members(long civilizationId) {
        return membersByCivilization.getOrDefault(civilizationId, List.of());
    }

    public List<Claim> claims(long civilizationId) {
        return claimsByCivilization.getOrDefault(civilizationId, List.of());
    }

    public Set<String> technologies(long civilizationId) {
        return technologies.getOrDefault(civilizationId, Set.of());
    }

    public List<ResearchEntry> research(long civilizationId) {
        return research.getOrDefault(civilizationId, List.of());
    }

    public War warFor(long civilizationId) {
        return warsByCivilization.get(civilizationId);
    }

    public War latestCampaign(long civilizationId) {
        War current = warFor(civilizationId);
        if (current != null) return current;
        return wars.values().stream()
            .filter(war -> war.attackerCivilizationId() == civilizationId || war.defenderCivilizationId() == civilizationId)
            .max(java.util.Comparator.comparing(War::declaredAt))
            .orElse(null);
    }

    public int establishedNonLeaders(long civilizationId) {
        return (int) members(civilizationId).stream().filter(Member::established).filter(m -> m.role() != Role.LEADER).count();
    }

    private static <K, V> Map<K, List<V>> immutableLists(Map<K, List<V>> input) {
        java.util.LinkedHashMap<K, List<V>> result = new java.util.LinkedHashMap<>();
        input.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    private static <K, V> Map<K, Set<V>> immutableSets(Map<K, Set<V>> input) {
        java.util.LinkedHashMap<K, Set<V>> result = new java.util.LinkedHashMap<>();
        input.forEach((key, value) -> result.put(key, Set.copyOf(value)));
        return Map.copyOf(result);
    }
}
