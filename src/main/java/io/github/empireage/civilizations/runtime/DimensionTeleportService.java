package io.github.empireage.civilizations.runtime;

import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.service.progression.CapabilityPolicy;
import io.github.empireage.civilizations.service.progression.TechnologyAccess;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.HeightMap;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.util.Vector;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.HandlerList;
import io.github.empireage.civilizations.runtime.DimensionRitualItems.Ritual;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import java.util.logging.Level;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Technology-gated random Nether/End travel, with a configured Overworld return route. */
public final class DimensionTeleportService implements CommandExecutor, TabCompleter, Listener, AutoCloseable {
    private static final Set<Material> DANGEROUS_FLOORS = Set.of(
        Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
        Material.FIRE, Material.SOUL_FIRE, Material.CACTUS, Material.POWDER_SNOW,
        Material.SWEET_BERRY_BUSH, Material.POINTED_DRIPSTONE
    );

    private static final int MAX_ATTEMPTS = 64;
    private static final Duration SEARCH_TIMEOUT = Duration.ofMinutes(2);
    private final JavaPlugin plugin;
    private final TechnologyAccess technology;
    private final StateCache cache;
    private final Settings.Travel settings;
    private final Clock clock;
    private final RandomGenerator random;
    private final DimensionRitualItems rituals;
    // Main-thread round-robin queue: at most one candidate per two ticks across all players.
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private final Map<UUID, Instant> cooldowns = new ConcurrentHashMap<>();
    private BukkitTask task;
    private boolean closed;

    public DimensionTeleportService(JavaPlugin plugin, TechnologyAccess technology, StateCache cache, Settings.Travel settings) {
        this(plugin, technology, cache, settings, Clock.systemUTC(), ThreadLocalRandom.current());
    }

    DimensionTeleportService(JavaPlugin plugin, TechnologyAccess technology, StateCache cache,
                             Settings.Travel settings, Clock clock, RandomGenerator random) {
        this(plugin, technology, cache, settings, clock, random, new DimensionRitualItems(plugin));
    }

    DimensionTeleportService(JavaPlugin plugin, TechnologyAccess technology, StateCache cache,
                             Settings.Travel settings, Clock clock, RandomGenerator random, DimensionRitualItems rituals) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.technology = java.util.Objects.requireNonNull(technology, "technology");
        this.cache = java.util.Objects.requireNonNull(cache, "cache");
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.random = java.util.Objects.requireNonNull(random, "random");
        this.rituals = java.util.Objects.requireNonNull(rituals, "rituals");
    }

    public void start() {
        if (task == null && !closed) {
            rituals.registerRecipes();
            plugin.getServer().getPluginManager().registerEvents(rituals, plugin);
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 2L);
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Only players can use dimension travel."); return true; }
        if (!player.hasPermission("civilizations.use")) { player.sendMessage("You do not have permission to use dimension travel."); return true; }
        if (args.length != 0) { player.sendMessage("Usage: /" + command.getName()); return true; }
        player.sendMessage(teleport(player, command.getName()).message());
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    public OperationResult teleport(Player player, String requestedTarget) {
        Target target = Target.parse(requestedTarget);
        if (target == null) return OperationResult.denied("Choose overworld, nether, or end.");
        if (closed || !player.isOnline() || player.isDead()) return OperationResult.denied("Dimension travel is unavailable right now.");
        if (pending.containsKey(player.getUniqueId())) return OperationResult.denied("A dimension teleport is already in progress.");
        Instant now = clock.instant();
        Instant availableAt = cooldowns.get(player.getUniqueId());
        if (availableAt != null && now.isBefore(availableAt)) {
            long seconds = Math.max(1L, (Duration.between(now, availableAt).toMillis() + 999L) / 1000L);
            return OperationResult.denied("Dimension travel is on cooldown for " + seconds + " seconds.");
        }
        OperationResult permission = authorize(player, target);
        if (!permission.success()) return permission;
        if (target != Target.OVERWORLD && !cache.ready()) return OperationResult.denied("Territory data is still loading. Try again shortly.");
        OperationResult returnRoute = validateReturnRoute(target);
        if (!returnRoute.success()) return returnRoute;
        Settings.Destination configured = configured(target);
        World world = Bukkit.getWorld(configured.world());
        if (world == null || world.getEnvironment() != target.environment)
            return OperationResult.denied("The configured " + target.label + " world '" + configured.world() + "' is unavailable or has the wrong dimension.");
        if (target == Target.OVERWORLD) {
            if (player.getWorld().getUID().equals(world.getUID()))
                return OperationResult.denied("You are already in the configured overworld world.");
            ResolvedDestination resolved = resolve(target);
            if (resolved.failure() != null) return OperationResult.denied(resolved.failure());
        } else if (WildTeleportService.bounds(world.getWorldBorder()) == null) {
            return OperationResult.denied("The " + target.label + " border has no safe space for random travel.");
        }
        Instant readyAt = now.plus(settings.warmup());
        pending.put(player.getUniqueId(), new Pending(player, target, readyAt, readyAt.plus(SEARCH_TIMEOUT)));
        return OperationResult.ok("Preparing travel to the " + target.label + " (" + settings.warmup().toSeconds()
            + " second warmup). Do not move or take damage while your destination is found.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Pending value = pending.get(event.getPlayer().getUniqueId());
        if (value == null || value.completing || event.getTo() == null) return;
        if (value.origin.getWorld() != event.getTo().getWorld()
            || value.origin.distanceSquared(event.getTo()) > 0.25D) {
            cancel(event.getPlayer(), "Dimension teleport cancelled because you moved.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) cancel(player, "Dimension teleport cancelled because you took damage.");
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { pending.remove(event.getPlayer().getUniqueId()); }

    void tick() {
        if (closed || pending.isEmpty()) return;
        var entry = pending.entrySet().iterator().next();
        Pending value = entry.getValue();
        pending.remove(entry.getKey());
        pending.put(entry.getKey(), value);
        Player player = value.player;
        if (!player.isOnline() || player.isDead()) { pending.remove(player.getUniqueId()); return; }
        Instant now = clock.instant();
        if (!now.isBefore(value.deadline)) { cancel(player, "The dimension search timed out. Try again; no cooldown was used."); return; }
        if (now.isBefore(value.readyAt)) return;
        try {
            OperationResult permission = authorize(player, value.target);
            if (!permission.success()) { cancel(player, permission.message()); return; }
            Location destination;
            if (value.target == Target.OVERWORLD) {
                ResolvedDestination resolved = resolve(value.target);
                if (resolved.failure() != null) { cancel(player, resolved.failure()); return; }
                destination = resolved.location();
            } else {
                if (!cache.ready()) return;
                World world = Bukkit.getWorld(configured(value.target).world());
                if (world == null || world.getEnvironment() != value.target.environment) {
                    cancel(player, "The configured " + value.target.label + " world is unavailable."); return;
                }
                WildTeleportService.Bounds bounds = WildTeleportService.bounds(world.getWorldBorder());
                if (bounds == null) { cancel(player, "The dimension border has no safe space for random travel."); return; }
                if (value.attempts++ >= MAX_ATTEMPTS) { cancel(player, "No safe unclaimed ground was found. Try again; no cooldown was used."); return; }
                int x = random.nextInt(bounds.minX(), bounds.maxX() + 1);
                int z = random.nextInt(bounds.minZ(), bounds.maxZ() + 1);
                if (claimed(world, x, z)) return;
                destination = randomLanding(world, x, z, player.getLocation().getYaw());
                if (destination == null || !cache.ready() || claimed(world, x, z)) return;
                OperationResult returnRoute = validateReturnRoute(value.target);
                if (!returnRoute.success()) { cancel(player, returnRoute.message()); return; }
                // Chunk-load callbacks may have cancelled the request or changed access/borders.
                if (pending.get(player.getUniqueId()) != value) return;
                permission = authorize(player, value.target);
                if (!permission.success()) { cancel(player, permission.message()); return; }
                if (!cache.ready() || claimed(world, x, z) || !insideBorder(destination)) return;
            }
            if (pending.get(player.getUniqueId()) != value) return;
            value.completing = true;
            DimensionRitualItems.Offering offering = value.target.ritual == null ? null : rituals.take(player, value.target.ritual);
            if (value.target.ritual != null && offering == null) {
                cancel(player, missingOffering(value.target));
                return;
            }
            boolean teleported = false;
            try {
                teleported = player.teleport(destination, TeleportCause.COMMAND);
            } finally {
                if (!teleported && offering != null) offering.refund();
            }
            if (teleported) {
                cooldowns.put(player.getUniqueId(), clock.instant().plus(settings.cooldown()));
                player.setFallDistance(0);
                player.setVelocity(new Vector());
                player.sendMessage("Teleported to the " + value.target.label + ".");
            } else player.sendMessage("The dimension teleport was cancelled. No cooldown was used.");
            pending.remove(player.getUniqueId());
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "Dimension destination search failed for " + player.getUniqueId(), failure);
            cancel(player, "Dimension travel could not finish. Try again.");
        }
    }

    private boolean claimed(World world, int x, int z) {
        return cache.snapshot().claim(new ChunkKey(world.getUID(), x >> 4, z >> 4)) != null;
    }

    static Location randomLanding(World world, int x, int z, float yaw) {
        if (world.getEnvironment() == World.Environment.THE_END) {
            int floorY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            return safePatch(world, x, floorY, z, yaw, true);
        }
        if (world.getEnvironment() != World.Environment.NETHER) return null;
        // Search cavern floors below the logical ceiling; never land on the Nether roof.
        int ceiling = Math.min(world.getMaxHeight(), world.getMinHeight() + world.getLogicalHeight());
        for (int floorY = ceiling - 3; floorY >= world.getMinHeight(); floorY--) {
            Location location = safePatch(world, x, floorY, z, yaw, false);
            if (location != null) return location;
        }
        return null;
    }

    private static Location safePatch(World world, int x, int floorY, int z, float yaw, boolean end) {
        if (floorY < world.getMinHeight() || floorY + 2 >= world.getMaxHeight()) return null;
        if (!safeColumn(world, x, floorY, z, end)) return null;
        // A clear, supported 3x3 patch avoids island edges and immediately adjacent lava/fire.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && !safeColumn(world, x + dx, floorY, z + dz, end)) return null;
            }
        }
        Location location = new Location(world, x + 0.5D, floorY + 1, z + 0.5D, yaw, 0);
        return insideBorder(location) ? location : null;
    }

    private static boolean safeColumn(World world, int x, int y, int z, boolean end) {
        Block floor = world.getBlockAt(x, y, z);
        Material material = floor.getType();
        if (floor.isLiquid() || DANGEROUS_FLOORS.contains(material)) return false;
        if (end ? material != Material.END_STONE : material == Material.BEDROCK || !material.isOccluding()) return false;
        return world.getBlockAt(x, y + 1, z).isEmpty() && world.getBlockAt(x, y + 2, z).isEmpty();
    }

    private static boolean insideBorder(Location location) {
        World world = location.getWorld();
        WildTeleportService.Bounds bounds = WildTeleportService.bounds(world.getWorldBorder());
        return bounds != null && bounds.contains(location.getBlockX(), location.getBlockZ())
            && world.getWorldBorder().isInside(location);
    }

    private OperationResult authorize(Player player, Target target) {
        if (target.capability != null && !technology.permitted(player, Set.of(target.capability)))
            return OperationResult.denied("Your civilization has not unlocked " + target.technologyName + ".");
        if (target.ritual != null && !rituals.has(player, target.ritual))
            return OperationResult.denied(missingOffering(target));
        return OperationResult.ok("Dimension travel authorized.");
    }

    private String missingOffering(Target target) {
        return "You need a " + target.ritual.displayName() + " in your inventory. See /civ items > Other Items. "
            + "One offering is consumed per successful trip.";
    }

    private OperationResult validateReturnRoute(Target target) {
        if (target == Target.OVERWORLD) return OperationResult.ok("Return route not required.");
        ResolvedDestination overworld = resolve(Target.OVERWORLD);
        return overworld.failure() == null
            ? OperationResult.ok("Overworld return route is available.")
            : OperationResult.denied("Dimension travel is unavailable because the Overworld return route is unsafe: "
                + overworld.failure());
    }

    private Settings.Destination configured(Target target) {
        return switch (target) {
            case OVERWORLD -> settings.overworld();
            case NETHER -> settings.nether();
            case END -> settings.end();
        };
    }

    private ResolvedDestination resolve(Target target) {
        Settings.Destination configured = configured(target);
        World world = Bukkit.getWorld(configured.world());
        if (world == null) return ResolvedDestination.failure(
            "The configured " + target.label + " world '" + configured.world() + "' is unavailable.");
        if (world.getEnvironment() != target.environment) return ResolvedDestination.failure(
            "The configured world '" + configured.world() + "' is not a " + target.label + " world.");
        Location anchor = configured.usesWorldSpawn()
            ? world.getSpawnLocation().clone()
            : new Location(world, configured.x(), configured.y(), configured.z(), configured.yaw(), configured.pitch());
        Location safe = findSafeLocation(anchor, settings.safeSearchRadius());
        return safe == null
            ? ResolvedDestination.failure("No safe arrival point exists near the configured " + target.label + " destination.")
            : new ResolvedDestination(safe, null);
    }

    static Location findSafeLocation(Location anchor, int radius) {
        World world = anchor.getWorld();
        if (world == null) return null;
        int baseX = anchor.getBlockX();
        int baseY = anchor.getBlockY();
        int baseZ = anchor.getBlockZ();
        for (int horizontal = 0; horizontal <= radius; horizontal++) {
            for (int dx = -horizontal; dx <= horizontal; dx++) {
                for (int dz = -horizontal; dz <= horizontal; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != horizontal) continue;
                    for (int vertical = 0; vertical <= 8; vertical++) {
                        int[] candidates = vertical == 0 ? new int[]{baseY} : new int[]{baseY + vertical, baseY - vertical};
                        for (int y : candidates) {
                            if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) continue;
                            Block feet = world.getBlockAt(baseX + dx, y, baseZ + dz);
                            Block head = world.getBlockAt(baseX + dx, y + 1, baseZ + dz);
                            Block floor = world.getBlockAt(baseX + dx, y - 1, baseZ + dz);
                            if (!feet.isPassable() || feet.isLiquid() || !head.isPassable() || head.isLiquid()
                                || !floor.getType().isSolid() || DANGEROUS_FLOORS.contains(floor.getType())) continue;
                            Location result = new Location(world, feet.getX() + 0.5D, y, feet.getZ() + 0.5D,
                                anchor.getYaw(), anchor.getPitch());
                            if (world.getWorldBorder().isInside(result)) return result;
                        }
                    }
                }
            }
        }
        return null;
    }

    private void cancel(Player player, String message) {
        Pending value = pending.remove(player.getUniqueId());
        if (value == null) return;
        player.sendMessage(message);
    }

    @Override
    public void close() {
        closed = true;
        if (task != null) {
            task.cancel();
            rituals.unregisterRecipes();
            HandlerList.unregisterAll(rituals);
            task = null;
        }
        pending.clear();
        cooldowns.clear();
    }

    private enum Target {
        OVERWORLD("overworld", World.Environment.NORMAL, null, null, null),
        NETHER("nether", World.Environment.NETHER, CapabilityPolicy.TELEPORT_NETHER, "Nether Expedition", Ritual.NETHER),
        END("end", World.Environment.THE_END, CapabilityPolicy.TELEPORT_END, "End Expedition", Ritual.END);

        private final String label;
        private final World.Environment environment;
        private final String capability;
        private final String technologyName;
        private final Ritual ritual;

        Target(String label, World.Environment environment, String capability, String technologyName, Ritual ritual) {
            this.label = label;
            this.environment = environment;
            this.capability = capability;
            this.technologyName = technologyName;
            this.ritual = ritual;
        }

        static Target parse(String value) {
            if (value == null) return null;
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "overworld", "world" -> OVERWORLD;
                case "nether" -> NETHER;
                case "end" -> END;
                default -> null;
            };
        }
    }

    private static final class Pending {
        private final Player player;
        private final Location origin;
        private final Target target;
        private final Instant readyAt;
        private final Instant deadline;
        private int attempts;
        private boolean completing;

        private Pending(Player player, Target target, Instant readyAt, Instant deadline) {
            this.player = player;
            this.origin = player.getLocation().clone();
            this.target = target;
            this.readyAt = readyAt;
            this.deadline = deadline;
        }
    }

    private record ResolvedDestination(Location location, String failure) {
        static ResolvedDestination failure(String message) {
            return new ResolvedDestination(null, message);
        }
    }
}
