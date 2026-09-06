package io.github.empireage.civilizations.domain;

import java.time.Instant;
import java.util.UUID;

public record WarObjective(
    long id,
    long warId,
    long nominatingCivilizationId,
    long targetClaimId,
    ChunkKey target,
    ObjectiveState state,
    BlockPosition standard,
    long controlMillis,
    Instant lastProgressAt,
    UUID securedBy,
    Instant securedAt,
    long rowVersion
) {
    public record BlockPosition(UUID worldId, int x, int y, int z) {}
}
