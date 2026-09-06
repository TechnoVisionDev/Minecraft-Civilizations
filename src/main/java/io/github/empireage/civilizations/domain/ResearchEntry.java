package io.github.empireage.civilizations.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ResearchEntry(
    long id,
    long civilizationId,
    int slot,
    String technologyKey,
    String state,
    UUID startedBy,
    Instant startedAt,
    Instant completesAt,
    long knowledgeCost,
    Map<ResourceKey, Long> materialCosts,
    long rowVersion
) {}
