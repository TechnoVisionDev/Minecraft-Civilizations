package io.github.empireage.civilizations.runtime;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.domain.Claim;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.Civilization;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.War;
import io.github.empireage.civilizations.domain.WarState;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClaimEntryListener implements Listener {
    private final StateCache cache;
    private final Map<UUID, ChunkKey> lastChunk = new ConcurrentHashMap<>();
    private final Map<WarningKey, Long> borderWarnings = new ConcurrentHashMap<>();

    public ClaimEntryListener(StateCache cache) {
        this.cache = cache;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @SuppressWarnings("deprecation")
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getFrom().getWorld() != event.getTo().getWorld()) return;
        ChunkKey key = new ChunkKey(event.getTo().getWorld().getUID(), event.getTo().getBlockX() >> 4, event.getTo().getBlockZ() >> 4);
        if (key.equals(lastChunk.put(event.getPlayer().getUniqueId(), key)) || !cache.ready()) return;
        Claim claim = cache.snapshot().claim(key);
        if (claim == null) return;
        Civilization civilization = cache.snapshot().civilization(claim.civilizationId());
        if (civilization == null) return;
        String detail = claim.plotType().name();
        if (claim.plotType() == io.github.empireage.civilizations.domain.PlotType.FOR_SALE && claim.listingPrice() != null) {
            detail += " · price " + claim.listingPrice().toPlainString();
        }
        String message = ChatColor.GOLD + civilization.name() + ChatColor.GRAY + " — " + detail;
        Member member = cache.snapshot().member(event.getPlayer().getUniqueId());
        if ((member == null || member.civilizationId() != claim.civilizationId())
            && cache.snapshot().technologies(claim.civilizationId()).contains("fortification")) {
            warnDefenders(event.getPlayer(), civilization, claim);
        }
        if (member != null && member.civilizationId() != claim.civilizationId()) {
            War war = cache.snapshot().warFor(member.civilizationId());
            if (war != null && war.opponent(member.civilizationId()) != null
                && war.opponent(member.civilizationId()) == claim.civilizationId()
                && war.effectiveState(Instant.now()) == WarState.ACTIVE) {
                message = ChatColor.RED + "Enemy campaign territory: " + civilization.name() + ChatColor.GRAY + " — " + detail;
            }
        }
        if (claim.greeting() != null && !claim.greeting().isBlank()) message += ChatColor.GRAY + " — " + claim.greeting();
        Player player = event.getPlayer();
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastChunk.remove(event.getPlayer().getUniqueId());
        borderWarnings.keySet().removeIf(key -> key.entrant().equals(event.getPlayer().getUniqueId()));
    }

    private void warnDefenders(Player entrant, Civilization civilization, Claim claim) {
        WarningKey key = new WarningKey(entrant.getUniqueId(), civilization.id());
        long now = System.currentTimeMillis();
        Long previous = borderWarnings.put(key, now);
        if (previous != null && now - previous < 30_000L) return;
        String warning = ChatColor.RED + "Border warning: " + ChatColor.WHITE + entrant.getName()
            + ChatColor.GRAY + " entered " + civilization.name() + " territory at "
            + claim.key().x() + ", " + claim.key().z() + ".";
        for (Player online : Bukkit.getOnlinePlayers()) {
            Member defender = cache.snapshot().member(online.getUniqueId());
            if (defender != null && defender.civilizationId() == civilization.id()) online.sendMessage(warning);
        }
    }

    private record WarningKey(UUID entrant, long civilizationId) {}
}
