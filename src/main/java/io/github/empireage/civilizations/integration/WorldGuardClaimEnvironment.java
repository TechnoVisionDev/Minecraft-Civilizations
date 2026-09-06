package io.github.empireage.civilizations.integration;

import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.service.territory.ClaimEnvironmentPort;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exact chunk/region intersection check for WorldGuard 7+, loaded entirely by
 * reflection. It may be shared by ordinary claiming and founding preflight.
 */
public final class WorldGuardClaimEnvironment implements ClaimEnvironmentPort {
    private final JavaPlugin plugin;
    private final boolean failClosed;
    private final AtomicBoolean loggedFailure = new AtomicBoolean();
    private volatile Access access;
    private volatile String lastFailure;

    public WorldGuardClaimEnvironment(JavaPlugin plugin, boolean failClosed) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.failClosed = failClosed;
    }

    @Override
    public Check check(UUID actor, ChunkKey chunk, String worldName, String biomeKey) {
        Objects.requireNonNull(chunk, "chunk");
        Plugin worldGuard = plugin.getServer().getPluginManager().getPlugin("WorldGuard");
        if (worldGuard == null || !worldGuard.isEnabled()) return Check.allow();
        if (!Bukkit.isPrimaryThread()) return failure("WorldGuard region checks must run on the server thread.", null);
        World world = Bukkit.getWorld(chunk.worldId());
        if (world == null && worldName != null) world = Bukkit.getWorld(worldName);
        if (world == null) return failure("WorldGuard could not check a missing world for this chunk.", null);
        try {
            Access api = access;
            if (api == null || api.pluginClassLoader != worldGuard.getClass().getClassLoader()) {
                api = Access.load(worldGuard);
                access = api;
            }
            List<String> regions = api.regionsIntersecting(world, chunk);
            lastFailure = null;
            if (regions.isEmpty()) return Check.allow();
            return Check.deny("This chunk intersects protected WorldGuard region(s): " + String.join(", ", regions) + ".");
        } catch (ReflectiveOperationException | LinkageError exception) {
            return failure("WorldGuard region check failed: " + rootMessage(exception), exception);
        }
    }

    public IntegrationStatus status() {
        Plugin worldGuard = plugin.getServer().getPluginManager().getPlugin("WorldGuard");
        if (worldGuard == null || !worldGuard.isEnabled()) return IntegrationStatus.missing("WorldGuard");
        try {
            Access api = access;
            if (api == null || api.pluginClassLoader != worldGuard.getClass().getClassLoader()) {
                api = Access.load(worldGuard);
                access = api;
            }
            String detail = lastFailure == null ? "Region intersection checks are active."
                : "Loaded, but the last check failed: " + lastFailure;
            return new IntegrationStatus("WorldGuard", true, lastFailure == null,
                worldGuard.getDescription().getVersion(), detail);
        } catch (ReflectiveOperationException | LinkageError exception) {
            return new IntegrationStatus("WorldGuard", true, false, worldGuard.getDescription().getVersion(),
                "Unsupported or inaccessible API: " + rootMessage(exception));
        }
    }

    private Check failure(String message, Throwable error) {
        lastFailure = message;
        if (loggedFailure.compareAndSet(false, true)) {
            if (error == null) plugin.getLogger().warning(message);
            else plugin.getLogger().log(java.util.logging.Level.WARNING, message, error);
        }
        return failClosed ? Check.deny(message + " Claiming is fail-closed.") : Check.allow();
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private static final class Access {
        private final ClassLoader pluginClassLoader;
        private final Method worldGuardInstance;
        private final Method platform;
        private final Method regionContainer;
        private final Method adaptWorld;
        private final Method regionManager;
        private final Method vectorAt;
        private final Constructor<?> cuboid;
        private final Method applicableRegions;

        private Access(ClassLoader pluginClassLoader, Method worldGuardInstance, Method platform,
                       Method regionContainer, Method adaptWorld, Method regionManager, Method vectorAt,
                       Constructor<?> cuboid, Method applicableRegions) {
            this.pluginClassLoader = pluginClassLoader;
            this.worldGuardInstance = worldGuardInstance;
            this.platform = platform;
            this.regionContainer = regionContainer;
            this.adaptWorld = adaptWorld;
            this.regionManager = regionManager;
            this.vectorAt = vectorAt;
            this.cuboid = cuboid;
            this.applicableRegions = applicableRegions;
        }

        static Access load(Plugin worldGuard) throws ReflectiveOperationException {
            ClassLoader loader = worldGuard.getClass().getClassLoader();
            Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard", true, loader);
            Class<?> platformClass = Class.forName("com.sk89q.worldguard.internal.platform.WorldGuardPlatform", true, loader);
            Class<?> containerClass = Class.forName("com.sk89q.worldguard.protection.regions.RegionContainer", true, loader);
            Class<?> bukkitAdapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, loader);
            Class<?> worldEditWorldClass = Class.forName("com.sk89q.worldedit.world.World", true, loader);
            Class<?> regionManagerClass = Class.forName("com.sk89q.worldguard.protection.managers.RegionManager", true, loader);
            Class<?> blockVectorClass = Class.forName("com.sk89q.worldedit.math.BlockVector3", true, loader);
            Class<?> protectedRegionClass = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion", true, loader);
            Class<?> cuboidClass = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion", true, loader);
            return new Access(loader,
                worldGuardClass.getMethod("getInstance"),
                worldGuardClass.getMethod("getPlatform"),
                platformClass.getMethod("getRegionContainer"),
                bukkitAdapterClass.getMethod("adapt", World.class),
                containerClass.getMethod("get", worldEditWorldClass),
                blockVectorClass.getMethod("at", int.class, int.class, int.class),
                cuboidClass.getConstructor(String.class, blockVectorClass, blockVectorClass),
                regionManagerClass.getMethod("getApplicableRegions", protectedRegionClass));
        }

        List<String> regionsIntersecting(World world, ChunkKey chunk) throws ReflectiveOperationException {
            Object singleton = worldGuardInstance.invoke(null);
            Object platformValue = platform.invoke(singleton);
            Object container = regionContainer.invoke(platformValue);
            Object worldEditWorld = adaptWorld.invoke(null, world);
            Object manager = regionManager.invoke(container, worldEditWorld);
            if (manager == null) return List.of();
            int minimumX = Math.multiplyExact(chunk.x(), 16);
            int minimumZ = Math.multiplyExact(chunk.z(), 16);
            Object minimum = vectorAt.invoke(null, minimumX, world.getMinHeight(), minimumZ);
            Object maximum = vectorAt.invoke(null, Math.addExact(minimumX, 15), world.getMaxHeight() - 1,
                Math.addExact(minimumZ, 15));
            Object probe = cuboid.newInstance("__civilizations_claim_probe__", minimum, maximum);
            Object applicable = applicableRegions.invoke(manager, probe);
            if (!(applicable instanceof Iterable<?> iterable)) {
                throw new ReflectiveOperationException("WorldGuard ApplicableRegionSet is not iterable");
            }
            List<String> names = new ArrayList<>();
            for (Object region : iterable) {
                try {
                    names.add(String.valueOf(region.getClass().getMethod("getId").invoke(region)));
                } catch (NoSuchMethodException ignored) {
                    names.add(String.valueOf(region));
                }
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return List.copyOf(names);
        }
    }
}
