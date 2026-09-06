package io.github.empireage.civilizations.service.economy;

import io.github.empireage.civilizations.domain.PlotType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlotTaxServiceTest {
    @Test
    void onlyPrivateOwnershipIncludingResalesIsTaxable() {
        UUID owner = UUID.randomUUID();
        assertTrue(PlotTaxService.taxable(PlotType.PRIVATE, owner, null));
        assertTrue(PlotTaxService.taxable(PlotType.FOR_SALE, owner, "MEMBER"));
        for (PlotType publicType : new PlotType[]{PlotType.CAPITAL, PlotType.CIVIC, PlotType.COMMON})
            assertFalse(PlotTaxService.taxable(publicType, owner, null));
        assertFalse(PlotTaxService.taxable(PlotType.FOR_SALE, owner, "CIVILIZATION"));
        assertFalse(PlotTaxService.taxable(PlotType.PRIVATE, null, null));
    }

    @Test
    void acceptsZeroAndExactCurrencyAndRejectsInvalidRates() {
        assertEquals(new BigDecimal("0.00"), PlotTaxService.normalizeRate(BigDecimal.ZERO));
        assertEquals(new BigDecimal("12.34"), PlotTaxService.normalizeRate(new BigDecimal("12.34")));
        assertThrows(IllegalArgumentException.class, () -> PlotTaxService.normalizeRate(new BigDecimal("-1")));
        assertThrows(ArithmeticException.class, () -> PlotTaxService.normalizeRate(new BigDecimal("0.001")));
        assertThrows(IllegalArgumentException.class, () -> PlotTaxService.normalizeRate(new BigDecimal("1E100")));
    }

    @Test
    void ownershipCheckRejectsChangedCivilizationAcquisitionAndPublicConversion() {
        UUID owner = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-04T12:00:00Z");
        var account = new PlotTaxService.Account(1, UUID.randomUUID(), 2, owner, now, BigDecimal.ONE, now);
        assertTrue(PlotTaxService.sameOwnership(new PlotTaxService.Plot(1, 2, owner, PlotType.PRIVATE, null, now, true, "world", 1, 2), account));
        assertFalse(PlotTaxService.sameOwnership(new PlotTaxService.Plot(1, 3, owner, PlotType.PRIVATE, null, now, true, "world", 1, 2), account));
        assertFalse(PlotTaxService.sameOwnership(new PlotTaxService.Plot(1, 2, owner, PlotType.PRIVATE, null, now.plusSeconds(1), true, "world", 1, 2), account));
        assertFalse(PlotTaxService.sameOwnership(new PlotTaxService.Plot(1, 2, owner, PlotType.CIVIC, null, now, true, "world", 1, 2), account));
        assertFalse(PlotTaxService.sameOwnership(null, account));
    }
}
