package io.github.empireage.civilizations.service.progression;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressionPolicyTest {
    @Test
    void researchSpeedAndCancellationUseExactBoundaries() {
        assertEquals(Duration.ofMinutes(54), ResearchService.adjustedDuration(Duration.ofHours(1), 10));
        assertEquals(Duration.ofMinutes(3), ResearchService.adjustedDuration(Duration.ofHours(1), 500));
        Instant start = Instant.parse("2026-08-17T20:00:00Z");
        assertTrue(ResearchService.canCancel(start, start.plus(Duration.ofMinutes(5)), Duration.ofMinutes(5)));
        assertFalse(ResearchService.canCancel(start, start.plus(Duration.ofMinutes(5)).plusNanos(1), Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class,
            () -> ResearchService.adjustedDuration(Duration.ZERO, 10));
    }

}
