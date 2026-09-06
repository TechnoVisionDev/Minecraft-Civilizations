package io.github.empireage.civilizations.integration;

import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.service.territory.ClaimEnvironmentPort;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Reserves public arrival areas so ordinary founding and claims cannot surround dimension travel. */
public final class TravelArrivalClaimEnvironment implements ClaimEnvironmentPort {
    private final Settings.Travel settings;
    private final ClaimEnvironmentPort delegate;

    public TravelArrivalClaimEnvironment(Settings.Travel settings, ClaimEnvironmentPort delegate) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Check check(UUID actor, ChunkKey chunk, String worldName, String biomeKey) {
        for (Settings.Destination destination : List.of(settings.overworld(), settings.nether(), settings.end())) {
            ChunkKey arrival = arrivalChunk(destination);
            if (protectedChunk(chunk, arrival, settings.claimProtectionRadiusChunks())) {
                return Check.deny("Dimension-travel arrival areas cannot be claimed.");
            }
        }
        return delegate.check(actor, chunk, worldName, biomeKey);
    }

    private static ChunkKey arrivalChunk(Settings.Destination destination) {
        World world = Bukkit.getWorld(destination.world());
        if (world == null) return null;
        Location location = destination.usesWorldSpawn()
            ? world.getSpawnLocation()
            : new Location(world, destination.x(), destination.y(), destination.z());
        return new ChunkKey(world.getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    static boolean protectedChunk(ChunkKey candidate, ChunkKey arrival, int radius) {
        return arrival != null && arrival.worldId().equals(candidate.worldId())
            && Math.abs(arrival.x() - candidate.x()) <= radius
            && Math.abs(arrival.z() - candidate.z()) <= radius;
    }
}
