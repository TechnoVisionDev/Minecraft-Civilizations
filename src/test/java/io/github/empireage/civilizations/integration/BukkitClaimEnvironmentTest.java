package io.github.empireage.civilizations.integration;

import io.github.empireage.civilizations.domain.ChunkKey;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
class BukkitClaimEnvironmentTest {
    @Test
    void requiresTheWholeChunkToRemainInsideTheBorder() {
        UUID world = UUID.fromString("10000000-0000-0000-0000-000000000001");

        assertTrue(BukkitClaimEnvironment.containsChunk(0, 0, 100, new ChunkKey(world, 2, 0)));
        assertFalse(BukkitClaimEnvironment.containsChunk(0, 0, 100, new ChunkKey(world, 3, 0)));
        assertFalse(BukkitClaimEnvironment.containsChunk(0, 0, 100, new ChunkKey(world, -4, 0)));
    }
}
