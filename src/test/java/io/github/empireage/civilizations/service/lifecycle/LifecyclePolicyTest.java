package io.github.empireage.civilizations.service.lifecycle;

import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.domain.ResourceKey;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecyclePolicyTest {
    private static final Instant NOW = Instant.parse("2026-08-17T20:00:00Z");

    @Test
    void identityCanonicalizesWhitespaceAndUnicode() {
        LifecyclePolicy.IdentityValidation result = LifecyclePolicy.validateIdentity("  Éire   Republic  ");

        assertTrue(result.valid());
        assertEquals("Éire Republic", result.identity().name());
        assertEquals("éire republic", result.identity().normalizedName());
    }

    @Test
    void identityAllowsOnlyVisibleDocumentedNameCharacters() {
        assertTrue(LifecyclePolicy.validateIdentity("King's-Landing 7").valid());
        assertFalse(LifecyclePolicy.validateIdentity("AB").valid());
        assertFalse(LifecyclePolicy.validateIdentity("Amp&ersand").valid());
        assertFalse(LifecyclePolicy.validateIdentity("§aFormatted").valid());
        assertFalse(LifecyclePolicy.validateIdentity("Zero\u200bWidth").valid());
        assertFalse(LifecyclePolicy.validateIdentity("Line\nBreak").valid());
        assertFalse(LifecyclePolicy.validateIdentity("This civilization name has far too many characters").valid());
    }

    @Test
    void civilizationNameIdentityIsCaseInsensitive() {
        String first = LifecyclePolicy.validateIdentity("New Rome").identity().normalizedName();
        String second = LifecyclePolicy.validateIdentity("nEw rOmE").identity().normalizedName();

        assertEquals(first, second);
    }

    @Test
    void deniedIdentityNeverCarriesPartiallyValidatedData() {
        LifecyclePolicy.IdentityValidation result = LifecyclePolicy.validateIdentity("No");

        assertFalse(result.valid());
        assertNull(result.identity());
    }

    @Test
    void minecraftPlayerNamesAreBoundedAndAsciiSafe() {
        assertTrue(LifecyclePolicy.validatePlayerName("Player_123").success());
        assertTrue(LifecyclePolicy.validatePlayerName("A").success());
        assertFalse(LifecyclePolicy.validatePlayerName("").success());
        assertFalse(LifecyclePolicy.validatePlayerName("seventeen_chars___").success());
        assertFalse(LifecyclePolicy.validatePlayerName("name-with-dash").success());
        assertFalse(LifecyclePolicy.validatePlayerName("Étienne").success());
    }

    @Test
    void foundingPaymentValidatesBothMoneyAndEveryCivicResource() {
        ResourceKey masonry = ResourceKey.parse("masonry:1");
        ResourceKey timber = ResourceKey.parse("timber:1");
        FoundingInventory inventory = new FoundingInventory(Map.of(masonry, 4L, timber, 2L));
        Map<ResourceKey, Long> cost = Map.of(masonry, 4L, timber, 2L);

        assertTrue(LifecyclePolicy.validateFoundingPayment(inventory, cost,
            new BigDecimal("100.00"), new BigDecimal("100.00")).success());
        assertFalse(LifecyclePolicy.validateFoundingPayment(inventory, cost,
            new BigDecimal("99.99"), new BigDecimal("100.00")).success());
        assertFalse(LifecyclePolicy.validateFoundingPayment(new FoundingInventory(Map.of(masonry, 4L)), cost,
            new BigDecimal("100.00"), new BigDecimal("100.00")).success());
    }

    @Test
    void advisorLimitAddsOnlyPositiveTechnologyCapacity() {
        int limit = LifecyclePolicy.advisorLimit(3, List.of(
            Map.of("advisor-capacity", 2),
            Map.of("advisor-capacity", -50),
            Map.of("claim-capacity", 8)));

        assertEquals(5, limit);
        assertTrue(LifecyclePolicy.validateAdvisorPromotion(4, limit).success());
        assertFalse(LifecyclePolicy.validateAdvisorPromotion(5, limit).success());
        assertThrows(IllegalArgumentException.class, () -> LifecyclePolicy.advisorLimit(-1, List.of()));
    }

    @Test
    void establishmentRequiresAgeRecentPresenceAndWindowActivity() {
        Duration age = Duration.ofHours(72);
        Duration window = Duration.ofDays(14);
        Duration active = Duration.ofHours(2);

        assertTrue(LifecyclePolicy.established(NOW.minus(age), NOW.minus(window), active.toSeconds(), NOW,
            age, window, active), "threshold boundaries are inclusive");
        assertFalse(LifecyclePolicy.established(NOW.minus(age).plusSeconds(1), NOW, active.toSeconds(), NOW,
            age, window, active));
        assertFalse(LifecyclePolicy.established(NOW.minus(age), NOW.minus(window).minusSeconds(1), active.toSeconds(), NOW,
            age, window, active));
        assertFalse(LifecyclePolicy.established(NOW.minus(age), NOW, active.toSeconds() - 1, NOW,
            age, window, active));
    }

    @Test
    void activityObservationRejectsReversedIntervalsAndLargeFutureSkew() {
        assertTrue(LifecyclePolicy.validateActivityObservation(
            NOW, NOW.plusSeconds(300), NOW).success());
        assertFalse(LifecyclePolicy.validateActivityObservation(
            NOW, NOW.plusSeconds(301), NOW).success());
        assertFalse(LifecyclePolicy.validateActivityObservation(
            NOW.plusSeconds(1), NOW, NOW).success());
    }

    @Test
    void activityDeltaUsesPersistedTimestampInsteadOfADecreasingWindowTotal() {
        Instant intervalStart = NOW.minusSeconds(300);

        assertEquals(300, LifecyclePolicy.unrecordedActivitySeconds(intervalStart, NOW, intervalStart));
        assertEquals(120, LifecyclePolicy.unrecordedActivitySeconds(
            intervalStart, NOW, NOW.minusSeconds(120)), "overlap from an ambiguous retry is not counted twice");
        assertEquals(0, LifecyclePolicy.unrecordedActivitySeconds(
            intervalStart, NOW, NOW.plusSeconds(1)), "an older observation is already fully persisted");
    }

    @Test
    void kickConfirmationIsRequiredOnlyWhenPropertyWillTerminate() {
        assertFalse(LifecyclePolicy.kickConfirmationRequired(0, false));
        assertTrue(LifecyclePolicy.kickConfirmationRequired(1, false));
        assertFalse(LifecyclePolicy.kickConfirmationRequired(1, true));
        assertThrows(IllegalArgumentException.class, () -> LifecyclePolicy.kickConfirmationRequired(-1, true));
    }

    @Test
    void cooldownUsesExactConfiguredDuration() {
        assertEquals(NOW.plus(Duration.ofHours(24)), LifecyclePolicy.cooldownUntil(NOW, Duration.ofHours(24)));
        assertEquals(NOW, LifecyclePolicy.cooldownUntil(NOW, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> LifecyclePolicy.cooldownUntil(NOW, Duration.ofSeconds(-1)));
    }
}
