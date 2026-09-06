package io.github.empireage.civilizations.runtime;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.domain.Civilization;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.service.lifecycle.CivilizationLifecycleService;
import io.github.empireage.civilizations.util.MainThreadExecutor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Routes ordinary player messages to the selected global, local, or civilization audience. */
@SuppressWarnings("deprecation")
public final class CivilizationChat implements Listener {
    private final StateCache cache;
    private final CivilizationLifecycleService lifecycle;
    private final MainThreadExecutor mainThread;
    private final double localRadiusSquared;
    private final Map<UUID, ChatChannel> channels = new ConcurrentHashMap<>();
    private final Set<UUID> explicitlySelected = ConcurrentHashMap.newKeySet();

    public CivilizationChat(JavaPlugin plugin, StateCache cache, CivilizationLifecycleService lifecycle,
                            int localRadiusBlocks) {
        this.cache = cache;
        this.lifecycle = lifecycle;
        this.mainThread = new MainThreadExecutor(plugin);
        this.localRadiusSquared = (double) localRadiusBlocks * localRadiusBlocks;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        channels.put(playerId, ChatChannel.GLOBAL);
        explicitlySelected.remove(playerId);
        // Preserve the old persistent civ-chat preference. Players without one always start global.
        lifecycle.chatPreference(playerId).thenAccept(preference -> {
            if (preference.civilizationChat() && !explicitlySelected.contains(playerId)) {
                channels.put(playerId, ChatChannel.CIV);
            }
        }).exceptionally(error -> null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        channels.remove(playerId);
        explicitlySelected.remove(playerId);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        event.setCancelled(true);
        Player sender = event.getPlayer();
        String message = event.getMessage();
        ChatChannel channel = channel(sender.getUniqueId());
        mainThread.run(() -> {
            OperationResult result = send(sender, message, channel);
            if (!result.success()) sender.sendMessage(ChatColor.RED + result.message());
        });
    }

    public ChatChannel channel(UUID playerId) {
        return channels.getOrDefault(playerId, ChatChannel.GLOBAL);
    }

    /** Compatibility behavior for `/civ chat`: toggle between civ and the default global channel. */
    public CompletableFuture<OperationResult> toggle(Player player) {
        ChatChannel next = channel(player.getUniqueId()) == ChatChannel.CIV ? ChatChannel.GLOBAL : ChatChannel.CIV;
        return select(player, next);
    }

    public CompletableFuture<OperationResult> select(Player player, ChatChannel channel) {
        if (!player.hasPermission("civilizations.chat")) {
            return CompletableFuture.completedFuture(OperationResult.denied("You do not have permission to switch chat channels."));
        }
        UUID playerId = player.getUniqueId();
        if (channel == ChatChannel.CIV) {
            if (cache.snapshot().member(playerId) == null) {
                return CompletableFuture.completedFuture(OperationResult.denied("You do not belong to a civilization."));
            }
            return lifecycle.setCivilizationChat(playerId, player.getName(), true).thenApply(result -> {
                if (result.success()) rememberSelection(playerId, ChatChannel.CIV);
                return result.success() ? OperationResult.ok("Chat channel set to civ.") : result;
            });
        }

        rememberSelection(playerId, channel);
        // Global/local selection is available even if storage is temporarily unavailable. Clearing
        // an old persistent civ preference is best-effort and only applies to civilization members.
        if (cache.snapshot().member(playerId) != null) {
            lifecycle.setCivilizationChat(playerId, player.getName(), false).exceptionally(error -> null);
        }
        return CompletableFuture.completedFuture(OperationResult.ok("Chat channel set to " + channel.displayName() + "."));
    }

    private void rememberSelection(UUID playerId, ChatChannel channel) {
        explicitlySelected.add(playerId);
        channels.put(playerId, channel);
    }

    /** Sends an explicit civilization message for the legacy `/civ chat <message>` command. */
    public OperationResult send(Player sender, String message) {
        return send(sender, message, ChatChannel.CIV);
    }

    OperationResult send(Player sender, String message, ChatChannel channel) {
        return switch (channel) {
            case GLOBAL -> sendGlobal(sender, message);
            case LOCAL -> sendLocal(sender, message);
            case CIV -> sendCivilization(sender, message);
        };
    }

    private OperationResult sendGlobal(Player sender, String message) {
        String formatted = format(ChatColor.AQUA, "Global", sender, message);
        Bukkit.getOnlinePlayers().forEach(recipient -> recipient.sendMessage(formatted));
        Bukkit.getConsoleSender().sendMessage(formatted);
        return OperationResult.ok("");
    }

    private OperationResult sendLocal(Player sender, String message) {
        String formatted = format(ChatColor.GREEN, "Local", sender, message);
        Location origin = sender.getLocation();
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            Location destination = recipient.getLocation();
            if (origin.getWorld() == destination.getWorld()
                && origin.distanceSquared(destination) <= localRadiusSquared) {
                recipient.sendMessage(formatted);
            }
        }
        Bukkit.getConsoleSender().sendMessage(formatted);
        return OperationResult.ok("");
    }

    private OperationResult sendCivilization(Player sender, String message) {
        if (!sender.hasPermission("civilizations.chat")) {
            return OperationResult.denied("You do not have permission to use civilization chat.");
        }
        Member member = cache.snapshot().member(sender.getUniqueId());
        if (member == null) return OperationResult.denied("You do not belong to a civilization.");
        Civilization civilization = cache.snapshot().civilization(member.civilizationId());
        if (civilization == null) return OperationResult.denied("Your civilization is unavailable.");
        String formatted = format(ChatColor.GOLD, civilization.name(), sender, message);
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            Member recipientMember = cache.snapshot().member(recipient.getUniqueId());
            if (recipientMember != null && recipientMember.civilizationId() == member.civilizationId()) {
                recipient.sendMessage(formatted);
            }
        }
        Bukkit.getConsoleSender().sendMessage(formatted);
        return OperationResult.ok("");
    }

    private static String format(ChatColor channelColor, String channelName, Player sender, String message) {
        return ChatColor.DARK_GRAY + "[" + channelColor + channelName + ChatColor.DARK_GRAY + "] "
            + ChatColor.YELLOW + sender.getName() + ChatColor.GRAY + ": " + ChatColor.WHITE + message;
    }
}
