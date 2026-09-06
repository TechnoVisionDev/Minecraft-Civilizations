package io.github.empireage.civilizations.util;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class TimeUtil {
    private TimeUtil() {}

    public static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    public static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public static String relative(Instant target, Instant now) {
        long seconds = ChronoUnit.SECONDS.between(now, target);
        boolean past = seconds < 0;
        seconds = Math.abs(seconds);
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        String value = days > 0 ? days + "d " + hours + "h" : hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
        return past ? value + " ago" : "in " + value;
    }
}
