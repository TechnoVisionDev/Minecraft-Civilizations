package io.github.empireage.civilizations.domain;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record TechnologyDefinition(
    String key,
    String name,
    String era,
    List<String> prerequisites,
    long knowledgeCost,
    Duration duration,
    Map<ResourceKey, Long> materialCosts,
    Set<String> capabilities,
    Map<String, Integer> modifiers
) {}
