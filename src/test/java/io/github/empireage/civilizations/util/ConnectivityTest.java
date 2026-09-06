package io.github.empireage.civilizations.util;

import io.github.empireage.civilizations.domain.ChunkKey;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectivityTest {
    private final UUID world = UUID.randomUUID();

    @Test
    void rejectsRemovalOfArticulationChunk() {
        ChunkKey capital = key(0, 0);
        ChunkKey bridge = key(1, 0);
        ChunkKey frontier = key(2, 0);
        assertFalse(Connectivity.remainsConnectedAfterRemoval(Set.of(capital, bridge, frontier), capital, bridge));
    }

    @Test
    void permitsRemovalWhenAlternateCardinalRouteExists() {
        ChunkKey capital = key(0, 0);
        ChunkKey candidate = key(1, 0);
        Set<ChunkKey> square = Set.of(capital, candidate, key(0, 1), key(1, 1));
        assertTrue(Connectivity.remainsConnectedAfterRemoval(square, capital, candidate));
    }

    @Test
    void diagonalContactDoesNotConnectTerritory() {
        assertFalse(Connectivity.allConnected(Set.of(key(0, 0), key(1, 1)), key(0, 0)));
    }

    private ChunkKey key(int x, int z) {
        return new ChunkKey(world, x, z);
    }
}
