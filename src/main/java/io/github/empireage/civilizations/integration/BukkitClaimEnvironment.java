package io.github.empireage.civilizations.integration;

import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.service.territory.ClaimEnvironmentPort;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldBorder;

import java.util.Objects;
import java.util.UUID;

/** Always-on world existence and world-border validation, optionally followed by another region policy. */
public final class BukkitClaimEnvironment implements ClaimEnvironmentPort {
    private final ClaimEnvironmentPort delegate;

    public BukkitClaimEnvironment(ClaimEnvironmentPort delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Check check(UUID actor, ChunkKey chunk, String worldName, String biomeKey) {
        Objects.requireNonNull(chunk, "chunk");
        if (!Bukkit.isPrimaryThread()) {
            return Check.deny("World-border checks must run on the server thread. Claiming is fail-closed.");
        }
        World world = Bukkit.getWorld(chunk.worldId());
        if (world == null && worldName != null) world = Bukkit.getWorld(worldName);
        if (world == null) return Check.deny("That world is not currently loaded; new claims are blocked.");
        if (!containsChunk(world.getWorldBorder(), chunk)) {
            return Check.deny("The entire chunk must be inside the current world border.");
        }
        return delegate.check(actor, chunk, world.getName(), biomeKey);
    }

    static boolean containsChunk(WorldBorder border, ChunkKey chunk) {
        return containsChunk(border.getCenter().getX(), border.getCenter().getZ(), border.getSize(), chunk);
    }

    static boolean containsChunk(double centerX, double centerZ, double size, ChunkKey chunk) {
        double half = size / 2.0D;
        double borderMinX = centerX - half;
        double borderMaxX = centerX + half;
        double borderMinZ = centerZ - half;
        double borderMaxZ = centerZ + half;
        double chunkMinX = (long) chunk.x() * 16L + 0.5D;
        double chunkMaxX = (long) chunk.x() * 16L + 15.5D;
        double chunkMinZ = (long) chunk.z() * 16L + 0.5D;
        double chunkMaxZ = (long) chunk.z() * 16L + 15.5D;
        return chunkMinX >= borderMinX && chunkMaxX < borderMaxX
            && chunkMinZ >= borderMinZ && chunkMaxZ < borderMaxZ;
    }
}
