package io.github.empireage.civilizations.service.economy;

import io.github.empireage.civilizations.service.territory.EconomyPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

class EconomyOperationStateTest {
    @Test
    void onlyProviderDispatchPhasesAreAmbiguous() {
        assertTrue(EconomyOperationState.WITHDRAWAL_IN_FLIGHT.ambiguous());
        assertTrue(EconomyOperationState.DELIVERY_IN_FLIGHT.ambiguous());
        assertTrue(EconomyOperationState.REFUND_IN_FLIGHT.ambiguous());
        assertFalse(EconomyOperationState.PENDING.ambiguous());
        assertFalse(EconomyOperationState.EXTERNAL_APPLIED.ambiguous());
        assertFalse(EconomyOperationState.DB_APPLIED.ambiguous());
    }

    @Test
    void onlyCompletedAndFailedAreTerminal() {
        assertTrue(EconomyOperationState.COMPLETED.terminal());
        assertTrue(EconomyOperationState.FAILED.terminal());
        assertFalse(EconomyOperationState.COMPENSATION_PENDING.terminal());
        assertFalse(EconomyOperationState.PENDING_DELIVERY.terminal());
    }

    @Test
    void terminalHistoryCanDetachWhileEveryUnsettledStateRetainsItsClaim() {
        for (EconomyOperationState state : EconomyOperationState.values()) {
            if (EnumSet.of(EconomyOperationState.COMPLETED, EconomyOperationState.FAILED).contains(state)) {
                assertFalse(state.retainsDomainReference(), state.name());
            } else {
                assertTrue(state.retainsDomainReference(), state.name());
            }
        }
    }

    @Test
    void providerExceptionsRemainDistinctFromDefinitiveDeclines() {
        assertTrue(EconomyPort.Result.ambiguous("timeout after dispatch").ambiguous());
        assertFalse(EconomyPort.Result.failed("insufficient funds").ambiguous());
        assertFalse(EconomyPort.Result.ok("paid").ambiguous());
    }
}
