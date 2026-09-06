package io.github.empireage.civilizations.command;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.config.CatalogManager;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.domain.OperationResult;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CivTutorialCommandTest {
    private final TutorialBookService tutorial = mock(TutorialBookService.class);
    private final StateCache cache = mock(StateCache.class);
    private final Player player = mock(Player.class);
    private final CivCommand command = new CivCommand(cache, mock(Settings.class), mock(CatalogManager.class),
        mock(CivilizationLifecycleService.class), mock(TerritoryService.class), mock(PlotService.class),
        mock(ProtectionService.class), mock(ProgressionModule.class), mock(TreasuryService.class),
        mock(WarService.class), mock(CommandPorts.Ports.class), Runnable::run, mock(ConfirmationGui.class),
        mock(PlotTaxService.class), mock(PlotTaxRuntime.class), tutorial);

    @BeforeEach void setup() {
        when(player.hasPermission("civilizations.use")).thenReturn(true);
    }

    @Test void tutorialIsAvailableWithoutCivilizationOrReadyDatabase() {
        when(tutorial.give(player)).thenReturn(OperationResult.ok("Book delivered."));
        assertTrue(command.onCommand(player, null, "civ", new String[]{"TuToRiAl"}));
        verify(tutorial).give(player);
        verify(player).sendMessage(contains("Book delivered."));
        verifyNoInteractions(cache);
    }

    @Test void reportsCooldownDenial() {
        when(tutorial.give(player)).thenReturn(OperationResult.denied("Available in 1h 0m 0s."));
        command.onCommand(player, null, "civ", new String[]{"tutorial"});
        verify(player).sendMessage(contains("Available in 1h 0m 0s."));
    }

    @Test void rejectsExtraArgumentsBeforeDelivery() {
        command.onCommand(player, null, "civ", new String[]{"tutorial", "anotherPlayer"});
        verifyNoInteractions(tutorial);
        verify(player).sendMessage(contains("/civ tutorial"));
    }

    @Test void consoleCannotReceiveBook() {
        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission("civilizations.use")).thenReturn(true);
        command.onCommand(console, null, "civ", new String[]{"tutorial"});
        verifyNoInteractions(tutorial);
        verify(console).sendMessage(anyString());
    }

    @Test void permissionIsRequiredForCommandAndCompletion() {
        when(player.hasPermission("civilizations.use")).thenReturn(false);
        command.onCommand(player, null, "civ", new String[]{"tutorial"});
        verifyNoInteractions(tutorial);
        assertTrue(command.onTabComplete(player, null, "civ", new String[]{"tut"}).isEmpty());
    }

    @Test void tutorialAppearsInTabCompletionWithoutArguments() {
        assertEquals(List.of("tutorial"), command.onTabComplete(player, null, "civ", new String[]{"tut"}));
        assertTrue(command.onTabComplete(player, null, "civ", new String[]{"tutorial", ""}).isEmpty());
    }
}
