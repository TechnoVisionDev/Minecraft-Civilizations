package io.github.empireage.civilizations.command;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandInputTest {
    @Test
    void joinsCivilizationNamesWithoutEatingTheTag() {
        String[] args = {"create", "The", "Golden", "Coast", "TGC"};
        assertEquals("The Golden Coast", CommandInput.join(args, 1, args.length - 1));
    }

    @Test
    void validatesPagesAndCurrencyExactly() {
        assertEquals(3, CommandInput.positiveInt("3", "Page"));
        assertThrows(IllegalArgumentException.class, () -> CommandInput.positiveInt("0", "Page"));
        assertEquals(new BigDecimal("12.50"), CommandInput.currency("12.5", false));
        assertEquals(new BigDecimal("0.00"), CommandInput.currency("0", true));
        assertThrows(IllegalArgumentException.class, () -> CommandInput.currency("1.001", false));
        assertThrows(IllegalArgumentException.class, () -> CommandInput.currency("-1", true));
    }

    @Test
    void completionsAreCaseInsensitiveSortedAndPrefixOnly() {
        assertEquals(List.of("Administration", "advisor"),
            CommandInput.matching(List.of("war", "advisor", "Administration"), "AD"));
    }

    @Test
    void formatsUsefulCompactDurations() {
        assertEquals("2d 3h", CommandInput.duration(Duration.ofHours(51)));
        assertEquals("4m 5s", CommandInput.duration(Duration.ofSeconds(245)));
        assertEquals("9s", CommandInput.duration(Duration.ofSeconds(9)));
    }
}
