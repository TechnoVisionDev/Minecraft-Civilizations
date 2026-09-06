package io.github.empireage.civilizations.runtime;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.domain.Civilization;
import io.github.empireage.civilizations.domain.HomeLocation;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.OperationResult;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HomeTeleportService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final StateCache cache;
    private final Settings.Plots settings;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> cooldowns = new ConcurrentHashMap<>();

    public HomeTeleportService(JavaPlugin plugin, StateCache cache, Settings.Plots settings) {
        this.plugin = plugin;
        this.cache = cache;
        this.settings = settings;
    }

    public OperationResult teleport(Player player) {
        if (!settings.homeEnabled()) return OperationResult.denied("Civilization home teleporting is disabled.");
        if (!cache.ready()) return OperationResult.denied("Territory data is still loading.");
        Member member = cache.snapshot().member(player.getUniqueId());
        if (member == null) return OperationResult.denied("You do not belong to a civilization.");
        Civilization civilization = cache.snapshot().civilization(member.civilizationId());
        Instant availableAt = cooldowns.get(player.getUniqueId());
        if (availableAt != null && Instant.now().isBefore(availableAt)) return OperationResult.denied(
            "Civilization home is on cooldown for " + Duration.between(Instant.now(), availableAt).toSeconds() + " seconds.");
        if (pending.containsKey(player.getUniqueId())) return OperationResult.denied("A civilization-home teleport is already warming up.");
        World world = Bukkit.getWorld(civilization.home().worldId());
        if (world == null) return OperationResult.denied("The civilization home world is unavailable.");
        Location destination = safeLocation(world, civilization.home());
        if (destination == null) return OperationResult.denied("The civilization home is obstructed and has no nearby safe standing space.");
        long delay = settings.homeWarmup().toSeconds() * 20L;
        if (delay <= 0) {
            if (!player.teleport(destination)) return OperationResult.denied("The civilization-home teleport was rejected.");
            cooldowns.put(player.getUniqueId(), Instant.now().plus(settings.homeCooldown()));
            return OperationResult.ok("Teleported to the civilization home.");
        }
        Location origin = player.getLocation().clone();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> complete(player), delay);
        pending.put(player.getUniqueId(), new Pending(origin, task));
        return OperationResult.ok("Teleporting in " + settings.homeWarmup().toSeconds() + " seconds. Do not move or take damage.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Pending value = pending.get(event.getPlayer().getUniqueId());
        if (value == null || event.getTo() == null) return;
        if (value.origin().getWorld() != event.getTo().getWorld() || value.origin().distanceSquared(event.getTo()) > 0.25) {
            cancel(event.getPlayer(), "Civilization-home teleport cancelled because you moved.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) cancel(player, "Civilization-home teleport cancelled because you took damage.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Pending value = pending.remove(event.getPlayer().getUniqueId());
        if (value != null) value.task().cancel();
    }

    private void complete(Player player) {
        Pending value = pending.remove(player.getUniqueId());
        if (value == null || !player.isOnline()) return;
        if (!cache.ready()) {
            player.sendMessage("Civilization-home teleport cancelled because territory data is unavailable.");
            return;
        }
        Member member = cache.snapshot().member(player.getUniqueId());
        Civilization civilization = member == null ? null : cache.snapshot().civilization(member.civilizationId());
        if (civilization == null) {
            player.sendMessage("Civilization-home teleport cancelled because your membership changed.");
            return;
        }
        World world = Bukkit.getWorld(civilization.home().worldId());
        Location destination = world == null ? null : safeLocation(world, civilization.home());
        if (destination == null) {
            player.sendMessage("Civilization-home teleport cancelled because the current home is unavailable or obstructed.");
            return;
        }
        if (!player.teleport(destination)) {
            player.sendMessage("The civilization-home teleport was rejected.");
            return;
        }
        cooldowns.put(player.getUniqueId(), Instant.now().plus(settings.homeCooldown()));
        player.sendMessage("Teleported to the civilization home.");
    }

    private void cancel(Player player, String message) {
        Pending value = pending.remove(player.getUniqueId());
        if (value == null) return;
        value.task().cancel();
        player.sendMessage(message);
    }

    private Location safeLocation(World world, HomeLocation home) {
        int baseY = (int) Math.floor(home.y());
        for (int offset = 0; offset <= 5; offset++) {
            for (int direction : offset == 0 ? new int[]{0} : new int[]{offset, -offset}) {
                int y = baseY + direction;
                if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) continue;
                Location candidate = new Location(world, home.x(), y, home.z(), home.yaw(), home.pitch());
                if (candidate.getBlock().isPassable() && candidate.clone().add(0, 1, 0).getBlock().isPassable()
                    && candidate.clone().add(0, -1, 0).getBlock().getType().isSolid()) return candidate;
            }
        }
        return null;
    }

    @Override
    public void close() {
        pending.values().forEach(value -> value.task().cancel());
        pending.clear();
    }

    private record Pending(Location origin, BukkitTask task) {}
}
