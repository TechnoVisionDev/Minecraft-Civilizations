package io.github.empireage.civilizations.command;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.config.CatalogManager;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.domain.Role;
import io.github.empireage.civilizations.runtime.PlotTaxRuntime;
import io.github.empireage.civilizations.runtime.TutorialBookService;
import io.github.empireage.civilizations.service.economy.PlotTaxService;
import io.github.empireage.civilizations.service.economy.TreasuryService;
import io.github.empireage.civilizations.service.lifecycle.CivilizationLifecycleService;
import io.github.empireage.civilizations.service.progression.ProgressionModule;
import io.github.empireage.civilizations.service.territory.PlotService;
import io.github.empireage.civilizations.service.territory.ProtectionService;
import io.github.empireage.civilizations.service.territory.TerritoryService;
import io.github.empireage.civilizations.service.war.WarService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LeadershipClaimCommandTest {
    private final UUID id = UUID.randomUUID();
    private final CivilizationLifecycleService lifecycle = mock(CivilizationLifecycleService.class);
    private final StateCache cache = mock(StateCache.class);
    private final StateSnapshot snapshot = mock(StateSnapshot.class);
    private final Player player = mock(Player.class);
    private final CivCommand command = new CivCommand(cache, mock(Settings.class), mock(CatalogManager.class),
        lifecycle, mock(TerritoryService.class), mock(PlotService.class), mock(ProtectionService.class),
        mock(ProgressionModule.class), mock(TreasuryService.class), mock(WarService.class),
        mock(CommandPorts.Ports.class), Runnable::run, mock(ConfirmationGui.class),
        mock(PlotTaxService.class), mock(PlotTaxRuntime.class), mock(TutorialBookService.class));

    @BeforeEach void setup() {
        when(player.hasPermission("civilizations.use")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(id);
        when(cache.snapshot()).thenReturn(snapshot);
        when(snapshot.member(id)).thenReturn(new Member(1, id, "Citizen", Role.CITIZEN,
            Instant.EPOCH, Instant.EPOCH, 0, false, 0, false));
    }

    @Test void citizenCanRequestClaimAndAuthoritativeServiceDecidesEligibility() {
        when(lifecycle.claimLeadership(id)).thenReturn(CompletableFuture.completedFuture(
            OperationResult.ok("You are now the leader of Rome.")));
        command.onCommand(player, null, "civ", new String[]{"claimleadership"});
        verify(lifecycle).claimLeadership(id);
        verify(player).sendMessage(contains("You are now the leader of Rome."));
    }

    @Test void serviceDenialIsShownToPlayer() {
        when(lifecycle.claimLeadership(id)).thenReturn(CompletableFuture.completedFuture(
            OperationResult.denied("The civilization leader is online.")));
        command.onCommand(player, null, "civ", new String[]{"claimleadership"});
        verify(player).sendMessage(contains("leader is online"));
    }

    @Test void nonmembersExtraArgumentsAndConsoleCannotClaim() {
        command.onCommand(player, null, "civ", new String[]{"claimleadership", "OtherPlayer"});
        verify(player).sendMessage(contains("/civ claimleadership"));
        when(snapshot.member(id)).thenReturn(null);
        command.onCommand(player, null, "civ", new String[]{"claimleadership"});
        verify(player).sendMessage(contains("do not belong"));
        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission("civilizations.use")).thenReturn(true);
        command.onCommand(console, null, "civ", new String[]{"claimleadership"});
        verifyNoInteractions(lifecycle);
    }

    @Test void commandIsDiscoverableAndUsesNormalCivilizationPermission() {
        assertEquals(List.of("claimleadership"), command.onTabComplete(player, null, "civ", new String[]{"claiml"}));
        assertTrue(command.onTabComplete(player, null, "civ", new String[]{"claimleadership", ""}).isEmpty());
        when(player.hasPermission("civilizations.use")).thenReturn(false);
        command.onCommand(player, null, "civ", new String[]{"claimleadership"});
        assertTrue(command.onTabComplete(player, null, "civ", new String[]{"claiml"}).isEmpty());
        verifyNoInteractions(lifecycle);
    }
}
