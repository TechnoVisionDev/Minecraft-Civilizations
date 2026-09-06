package io.github.empireage.civilizations.command;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

final class CommandInput {
    private CommandInput() {}

    static String join(String[] values, int from) {
        return join(values, from, values.length);
    }

    static String join(String[] values, int from, int to) {
        if (from < 0 || to < from || to > values.length) throw new IndexOutOfBoundsException();
        return String.join(" ", java.util.Arrays.copyOfRange(values, from, to)).trim();
    }

    static int positiveInt(String value, String label) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(label + " must be a whole number of at least 1.");
        }
    }

    static BigDecimal currency(String value, boolean allowZero) {
        try {
            BigDecimal amount = new BigDecimal(value);
            if (amount.scale() > 2) throw new IllegalArgumentException("Currency amounts may have at most two decimal places.");
            if (amount.precision() > 18) throw new IllegalArgumentException("That currency amount is too large.");
            if (allowZero ? amount.signum() < 0 : amount.signum() <= 0) {
                throw new IllegalArgumentException(allowZero
                    ? "The amount cannot be negative." : "The amount must be greater than zero.");
            }
            return amount.setScale(2);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("That is not a valid currency amount.");
        }
    }

    static String technologyKey(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    static List<String> matching(Collection<String> candidates, String prefix) {
        String normalized = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate != null && candidate.toLowerCase(Locale.ROOT).startsWith(normalized)) result.add(candidate);
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(result);
    }

    static String duration(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) return "now";
        long seconds = duration.getSeconds();
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        long remainingSeconds = seconds % 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + remainingSeconds + "s";
        return remainingSeconds + "s";
    }
}
