package io.github.empireage.civilizations.util;

import java.time.Duration;
import java.util.Locale;

public final class DurationParser {
    private DurationParser() {}

    public static Duration parse(String input) {
        String value = input.strip().toLowerCase(Locale.ROOT);
        if (value.matches("\\d+[smhd]")) {
            long amount = Long.parseLong(value.substring(0, value.length() - 1));
            return switch (value.charAt(value.length() - 1)) {
                case 's' -> Duration.ofSeconds(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                case 'd' -> Duration.ofDays(amount);
                default -> throw new IllegalArgumentException("Unsupported duration: " + input);
            };
        }
        return Duration.parse(input);
    }
}
