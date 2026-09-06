package io.github.empireage.civilizations.util;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

public final class WarSchedule {
    private WarSchedule() {}

    public record Window(Instant start, Instant end, ZoneId zoneId) {}

    public static Window nextWindow(
        Instant declaredAt,
        ZoneId zone,
        DayOfWeek weekday,
        LocalTime startTime,
        LocalTime endTime,
        Duration notice
    ) {
        ZonedDateTime declared = declaredAt.atZone(zone);
        LocalDate candidateDate = declared.toLocalDate().with(TemporalAdjusters.nextOrSame(weekday));
        ZonedDateTime candidate = ZonedDateTime.of(candidateDate, startTime, zone);
        if (Duration.between(declared.toInstant(), candidate.toInstant()).compareTo(notice) < 0) {
            candidate = ZonedDateTime.of(candidateDate.plusWeeks(1), startTime, zone);
        }
        ZonedDateTime end = ZonedDateTime.of(candidate.toLocalDate(), endTime, zone);
        if (!end.isAfter(candidate)) end = end.plusDays(1);
        return new Window(candidate.toInstant(), end.toInstant(), zone);
    }

    public static boolean active(Instant now, Instant start, Instant end) {
        return !now.isBefore(start) && now.isBefore(end);
    }
}
