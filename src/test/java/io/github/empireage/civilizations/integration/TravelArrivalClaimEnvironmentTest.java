package io.github.empireage.civilizations.integration;

import io.github.empireage.civilizations.domain.ChunkKey;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TravelArrivalClaimEnvironmentTest {
    @Test
    void reservesConfiguredChunkRadiusOnlyInTheArrivalWorld() {
        UUID world = UUID.randomUUID();
        ChunkKey arrival = new ChunkKey(world, 10, -4);

        assertTrue(TravelArrivalClaimEnvironment.protectedChunk(new ChunkKey(world, 10, -4), arrival, 1));
        assertTrue(TravelArrivalClaimEnvironment.protectedChunk(new ChunkKey(world, 11, -5), arrival, 1));
        assertFalse(TravelArrivalClaimEnvironment.protectedChunk(new ChunkKey(world, 12, -4), arrival, 1));
        assertFalse(TravelArrivalClaimEnvironment.protectedChunk(
            new ChunkKey(UUID.randomUUID(), 10, -4), arrival, 1));
    }
}
