package io.github.empireage.civilizations.runtime;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.OperationResult;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.random.RandomGenerator;

/** Safe Overworld wilderness travel with a persistent manual cooldown and one free initial placement. */
public final class WildTeleportService implements CommandExecutor, TabCompleter, Listener, AutoCloseable {
    public static final Duration COOLDOWN = Duration.ofHours(12);
    private static final Duration SEARCH_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_ATTEMPTS = 64;
    private static final double WORLD_LIMIT = 29_999_984D;
    private static final double BORDER_MARGIN = 1D;
    private static final Set<Material> DANGEROUS_FLOORS = Set.of(
        Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.CACTUS,
        Material.POWDER_SNOW, Material.FIRE, Material.SOUL_FIRE, Material.SWEET_BERRY_BUSH,
        Material.POINTED_DRIPSTONE
    );

    private final JavaPlugin plugin;
    private final StateCache cache;
    private final String worldName;
    private final Clock clock;
    private final RandomGenerator random;
    private final NamespacedKey nextUseKey;
    private final NamespacedKey initialPendingKey;
    // All Bukkit work and queue access stays on the main thread. One global candidate per two ticks bounds generation work.
    private final Map<UUID, Request> requests = new LinkedHashMap<>();
    private BukkitTask task;
    private boolean closed;

    public WildTeleportService(JavaPlugin plugin, StateCache cache, String worldName) {
        this(plugin, cache, worldName, Clock.systemUTC(), ThreadLocalRandom.current());
    }

    WildTeleportService(JavaPlugin plugin, StateCache cache, String worldName, Clock clock, RandomGenerator random) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.worldName = Objects.requireNonNull(worldName, "worldName");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        nextUseKey = new NamespacedKey(plugin, "wild-next-use");
        initialPendingKey = new NamespacedKey(plugin, "wild-initial-pending");
    }

    public void start() {
        if (task != null || closed) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 2L);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (initialPending(player)) enqueue(player, true);
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Only players can use /wild."); return true; }
        if (!player.hasPermission("civilizations.wild")) { message(player, "&cYou do not have permission to use /wild."); return true; }
        if (args.length != 0) { message(player, "&eUsage: /wild"); return true; }
        OperationResult result = teleport(player);
        message(player, (result.success() ? "&e" : "&c") + result.message());
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    public OperationResult teleport(Player player) {
        return enqueue(player, initialPending(player));
    }

    private OperationResult enqueue(Player player, boolean initial) {
        if (closed || !player.isOnline()) return OperationResult.denied("Wilderness travel is unavailable right now.");
        if (player.isDead()) return OperationResult.denied("Respawn before using /wild.");
        if (requests.containsKey(player.getUniqueId())) return OperationResult.denied("Your wilderness destination is already being found.");
        Instant now = clock.instant();
        Long nextUse = player.getPersistentDataContainer().get(nextUseKey, PersistentDataType.LONG);
        if (!initial && nextUse != null && nextUse > now.toEpochMilli())
            return OperationResult.denied("You can use /wild again in " + remaining(nextUse - now.toEpochMilli()) + ".");
        if (!initial && !cache.ready()) return OperationResult.denied("Territory data is still loading. Try /wild again shortly.");
        World world = Bukkit.getWorld(worldName);
        if (world == null || world.getEnvironment() != World.Environment.NORMAL)
            return OperationResult.denied("The configured Overworld '" + worldName + "' is unavailable.");
        if (bounds(world.getWorldBorder()) == null)
            return OperationResult.denied("The Overworld border has no safe space for wilderness travel.");
        requests.put(player.getUniqueId(), new Request(player, initial, now.plus(SEARCH_TIMEOUT),
            initial ? now.plusSeconds(1) : now));
        return OperationResult.ok("Finding a safe wilderness destination inside the Overworld border...");
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPlayedBefore()) {
            player.getPersistentDataContainer().set(initialPendingKey, PersistentDataType.BYTE, (byte) 1);
            save(player);
        }
        if (!initialPending(player)) return;
        OperationResult result = enqueue(player, true);
        message(player, (result.success() ? "&e" : "&c") + result.message());
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { requests.remove(event.getPlayer().getUniqueId()); }

    void tick() {
        if (closed || requests.isEmpty()) return;
        var entry = requests.entrySet().iterator().next();
        Request request = entry.getValue();
        // Rotate without dropping the in-progress guard during teleport events.
        requests.remove(entry.getKey());
        requests.put(entry.getKey(), request);
        Player player = request.player;
        if (!player.isOnline() || player.isDead()) { requests.remove(player.getUniqueId()); return; }
        Instant now = clock.instant();
        if (!now.isBefore(request.deadline)) { fail(request, "The wilderness search timed out. Try /wild again."); return; }
        if (now.isBefore(request.notBefore) || !cache.ready()) return;
        try {
            World world = Bukkit.getWorld(worldName);
            if (world == null || world.getEnvironment() != World.Environment.NORMAL) {
                fail(request, "The configured Overworld is unavailable."); return;
            }
            Bounds bounds = bounds(world.getWorldBorder());
            if (bounds == null) { fail(request, "The Overworld border has no safe space for wilderness travel."); return; }
            int x = random.nextInt(bounds.minX(), bounds.maxX() + 1);
            int z = random.nextInt(bounds.minZ(), bounds.maxZ() + 1);
            request.attempts++;
            Location destination = null;
            if (cache.snapshot().claim(new ChunkKey(world.getUID(), x >> 4, z >> 4)) == null)
                destination = safeSurface(world, x, z, player.getLocation().getYaw());
            if (destination == null) {
                if (request.attempts >= MAX_ATTEMPTS) fail(request, "No safe unclaimed land was found inside the border. Try /wild again.");
                return;
            }
            if (!cache.ready() || cache.snapshot().claim(new ChunkKey(world.getUID(), x >> 4, z >> 4)) != null) return;
            if (!player.teleport(destination, request.initial ? TeleportCause.PLUGIN : TeleportCause.COMMAND)) {
                fail(request, "The wilderness teleport was cancelled."); return;
            }
            player.setFallDistance(0);
            player.setVelocity(new Vector());
            if (!request.initial)
                player.getPersistentDataContainer().set(nextUseKey, PersistentDataType.LONG, clock.instant().plus(COOLDOWN).toEpochMilli());
            player.getPersistentDataContainer().remove(initialPendingKey);
            save(player);
            requests.remove(player.getUniqueId());
            message(player, "&aTeleported to wilderness at " + x + ", " + z + ". "
                + (request.initial ? "This first-join teleport is free; you can use /wild again now."
                    : "You can use /wild again in 12 hours."));
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "Wilderness destination search failed for " + player.getUniqueId(), failure);
            fail(request, "Wilderness travel could not finish. Try /wild again.");
        }
    }

    static Bounds bounds(WorldBorder border) {
        Location center = border.getCenter();
        double size = border.getSize();
        if (!Double.isFinite(size) || size <= 0 || !Double.isFinite(center.getX()) || !Double.isFinite(center.getZ())) return null;
        double half = size / 2;
        double lowX = Math.max(-WORLD_LIMIT, center.getX() - half) + BORDER_MARGIN;
        double highX = Math.min(WORLD_LIMIT, center.getX() + half) - BORDER_MARGIN;
        double lowZ = Math.max(-WORLD_LIMIT, center.getZ() - half) + BORDER_MARGIN;
        double highZ = Math.min(WORLD_LIMIT, center.getZ() + half) - BORDER_MARGIN;
        // Land at block centers, keeping the player's whole body inside the border.
        int minX = (int) Math.ceil(lowX - 0.5), maxX = (int) Math.floor(highX - 0.5);
        int minZ = (int) Math.ceil(lowZ - 0.5), maxZ = (int) Math.floor(highZ - 0.5);
        return minX > maxX || minZ > maxZ ? null : new Bounds(minX, maxX, minZ, maxZ);
    }

    static Location safeSurface(World world, int x, int z, float yaw) {
        int floorY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (floorY < world.getMinHeight() || floorY + 2 >= world.getMaxHeight()) return null;
        Block floor = world.getBlockAt(x, floorY, z);
        Block feet = world.getBlockAt(x, floorY + 1, z);
        Block head = world.getBlockAt(x, floorY + 2, z);
        if (floor.isLiquid() || DANGEROUS_FLOORS.contains(floor.getType()) || !floor.getType().isSolid()
            || !feet.isEmpty() || !head.isEmpty()) return null;
        Location destination = new Location(world, x + 0.5, floorY + 1, z + 0.5, yaw, 0);
        Bounds current = bounds(world.getWorldBorder());
        if (current == null || !current.contains(x, z) || !world.getWorldBorder().isInside(destination)) return null;
        return destination;
    }

    private boolean initialPending(Player player) {
        return Byte.valueOf((byte) 1).equals(player.getPersistentDataContainer().get(initialPendingKey, PersistentDataType.BYTE));
    }

    private void fail(Request request, String explanation) {
        requests.remove(request.player.getUniqueId());
        message(request.player, "&c" + explanation + " No /wild cooldown was used.");
        // Retain the first-join marker so a failed initial placement can be retried for free.
    }

    private void save(Player player) {
        try { player.saveData(); }
        catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not immediately save wilderness travel state for " + player.getUniqueId()
                + "; the state remains in player data for the next normal save.", failure);
        }
    }

    static String remaining(long millis) {
        long seconds = Math.max(1, (millis + 999) / 1000);
        long hours = seconds / 3600, minutes = seconds % 3600 / 60, rest = seconds % 60;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes > 0 ? minutes + "m " + rest + "s" : rest + "s";
    }

    private static void message(Player player, String text) { player.sendMessage(ChatColor.translateAlternateColorCodes('&', text)); }

    @Override public void close() {
        closed = true;
        if (task != null) task.cancel();
        requests.clear();
    }

    record Bounds(int minX, int maxX, int minZ, int maxZ) {
        boolean contains(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
    }

    private static final class Request {
        private final Player player;
        private final boolean initial;
        private final Instant deadline;
        private final Instant notBefore;
        private int attempts;
        private Request(Player player, boolean initial, Instant deadline, Instant notBefore) {
            this.player = player; this.initial = initial; this.deadline = deadline; this.notBefore = notBefore;
        }
    }
}
