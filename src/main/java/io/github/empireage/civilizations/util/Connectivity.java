package io.github.empireage.civilizations.util;

import io.github.empireage.civilizations.domain.ChunkKey;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

public final class Connectivity {
    private Connectivity() {}

    public static boolean allConnected(Set<ChunkKey> chunks, ChunkKey capital) {
        if (chunks.isEmpty() || !chunks.contains(capital)) return false;
        Set<ChunkKey> visited = new HashSet<>();
        ArrayDeque<ChunkKey> queue = new ArrayDeque<>();
        queue.add(capital);
        visited.add(capital);
        while (!queue.isEmpty()) {
            ChunkKey current = queue.removeFirst();
            for (int[] direction : DIRECTIONS) {
                ChunkKey next = new ChunkKey(current.worldId(), current.x() + direction[0], current.z() + direction[1]);
                if (chunks.contains(next) && visited.add(next)) queue.addLast(next);
            }
        }
        return visited.size() == chunks.size();
    }

    public static boolean remainsConnectedAfterRemoval(Set<ChunkKey> chunks, ChunkKey capital, ChunkKey removal) {
        if (capital.equals(removal)) return false;
        Set<ChunkKey> remaining = new HashSet<>(chunks);
        remaining.remove(removal);
        return allConnected(remaining, capital);
    }

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
}
