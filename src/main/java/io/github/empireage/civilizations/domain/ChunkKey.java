package io.github.empireage.civilizations.domain;

import java.util.Objects;
import java.util.UUID;

public record ChunkKey(UUID worldId, int x, int z) {
    public ChunkKey {
        Objects.requireNonNull(worldId, "worldId");
    }

    public boolean cardinallyAdjacent(ChunkKey other) {
        return worldId.equals(other.worldId)
            && Math.abs((long) x - other.x) + Math.abs((long) z - other.z) == 1L;
    }

    public long chebyshevDistance(ChunkKey other) {
        if (!worldId.equals(other.worldId)) return Long.MAX_VALUE;
        return Math.max(Math.abs((long) x - other.x), Math.abs((long) z - other.z));
    }

    public String compact() {
        return worldId + ":" + x + ":" + z;
    }
}
