package io.github.empireage.civilizations.service.progression;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.database.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class DepositServicePolicyTest {
    @Test
    void restoresOnlyAfterAConclusiveAbsentLedgerRead() {
        assertTrue(DepositService.definitelyRolledBack(false, null));
        assertFalse(DepositService.definitelyRolledBack(true, null));
        assertFalse(DepositService.definitelyRolledBack(null, new IllegalStateException("storage unavailable")));
        assertFalse(DepositService.definitelyRolledBack(false, new IllegalStateException("read failed")));
    }

    @Test
    void closeStopsNewInventoryDebits() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            DepositService service = new DepositService(mock(JavaPlugin.class), mock(Database.class),
                mock(StateCache.class), mock(CivicItemService.class), mock(StockpileService.class),
                "test");

            service.close();

            DepositService.DepositResult result = service.deposit(mock(Player.class), DepositService.Scope.HAND).join();
            assertFalse(result.success());
            assertTrue(result.message().contains("stopping"));
        }
    }
}
