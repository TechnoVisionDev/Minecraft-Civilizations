package io.github.empireage.civilizations.runtime;

import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.service.lifecycle.CivilizationLifecycleService;
import io.github.empireage.civilizations.service.lifecycle.LifecycleModels.ActivityResult;
import io.github.empireage.civilizations.service.lifecycle.LifecycleModels.ActivityUpdate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActivityTrackerTest {
    @Test void loginAndLogoutPublishPresenceBeforePendingActivityWritesComplete() {
        CivilizationLifecycleService lifecycle = mock(CivilizationLifecycleService.class);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Leader");
        CompletableFuture<ActivityResult> save = new CompletableFuture<>();
        when(lifecycle.updateActivity(any())).thenReturn(save);
        ActivityTracker tracker = new ActivityTracker(mock(JavaPlugin.class), lifecycle);
        tracker.onJoin(new PlayerJoinEvent(player, ""));
        verify(lifecycle).recordPresence(eq(id), eq(true), any());
        ArgumentCaptor<ActivityUpdate> update = ArgumentCaptor.forClass(ActivityUpdate.class);
        verify(lifecycle).updateActivity(update.capture());
        assertEquals(id, update.getValue().playerId());
        tracker.onQuit(new PlayerQuitEvent(player, ""));
        verify(lifecycle).recordPresence(eq(id), eq(false), any());
        verify(lifecycle, times(1)).updateActivity(any());
        save.complete(new ActivityResult(OperationResult.ok("Saved"), false, false, null, 0));
        verify(lifecycle, times(2)).updateActivity(any());
    }

    @Test void startupTracksPlayersAlreadyOnline() {
        CivilizationLifecycleService lifecycle = mock(CivilizationLifecycleService.class);
        when(lifecycle.updateActivity(any())).thenReturn(CompletableFuture.completedFuture(
            new ActivityResult(OperationResult.ok("Saved"), false, false, null, 0)));
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Leader");
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            bukkit.when(Bukkit::getScheduler).thenReturn(mock(BukkitScheduler.class));
            new ActivityTracker(mock(JavaPlugin.class), lifecycle).start();
            verify(lifecycle).recordPresence(eq(id), eq(true), any());
            verify(lifecycle).updateActivity(any());
        }
    }
}
