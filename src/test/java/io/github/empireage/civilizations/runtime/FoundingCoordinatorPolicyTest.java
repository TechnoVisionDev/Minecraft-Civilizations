package io.github.empireage.civilizations.runtime;

import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.service.economy.EconomyService;
import io.github.empireage.civilizations.service.lifecycle.CivilizationLifecycleService;
import io.github.empireage.civilizations.service.progression.CivicItemService;
import io.github.empireage.civilizations.domain.OperationResult;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class FoundingCoordinatorPolicyTest {
    @Test
    void databaseFailureDenialsAreTreatedAsCommitAmbiguityWithoutADurableCharge() {
        assertTrue(FoundingCoordinator.uncertainLifecycleDenial(OperationResult.denied(
            "Could not create the civilization; no database changes were committed: connection reset")));
    }

    @Test
    void ordinaryPolicyDenialsAreKnownNotToHaveCommitted() {
        assertFalse(FoundingCoordinator.uncertainLifecycleDenial(
            OperationResult.denied("That civilization name is already in use.")));
        assertFalse(FoundingCoordinator.uncertainLifecycleDenial(OperationResult.ok("Civilization founded.")));
    }

    @Test
    void closeStopsNewFoundingDebits() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            FoundingCoordinator coordinator = new FoundingCoordinator(mock(JavaPlugin.class), mock(Settings.class),
                mock(CivicItemService.class), mock(CivilizationLifecycleService.class), mock(EconomyService.class));

            coordinator.close();

            OperationResult result = coordinator.create(mock(Player.class), "New Civ", false).join();
            assertFalse(result.success());
            assertTrue(result.message().contains("shutting down"));
        }
    }
}
