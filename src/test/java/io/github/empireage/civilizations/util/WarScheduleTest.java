package io.github.empireage.civilizations.util;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarScheduleTest {
    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");

    @Test
    void schedulesUpcomingSaturdayWhenNoticeIsSatisfied() {
        Instant declaration = ZonedDateTime.of(2026, 8, 13, 12, 0, 0, 0, LOS_ANGELES).toInstant();
        WarSchedule.Window window = WarSchedule.nextWindow(declaration, LOS_ANGELES, DayOfWeek.SATURDAY,
            LocalTime.of(14, 0), LocalTime.of(18, 0), Duration.ofHours(24));
        assertEquals(ZonedDateTime.of(2026, 8, 15, 14, 0, 0, 0, LOS_ANGELES).toInstant(), window.start());
        assertEquals(Duration.ofHours(4), Duration.between(window.start(), window.end()));
    }

    @Test
    void skipsAWeekWhenTwentyFourHourNoticeCannotBeMet() {
        Instant declaration = ZonedDateTime.of(2026, 8, 14, 15, 0, 0, 0, LOS_ANGELES).toInstant();
        WarSchedule.Window window = WarSchedule.nextWindow(declaration, LOS_ANGELES, DayOfWeek.SATURDAY,
            LocalTime.of(14, 0), LocalTime.of(18, 0), Duration.ofHours(24));
        assertEquals(ZonedDateTime.of(2026, 8, 22, 14, 0, 0, 0, LOS_ANGELES).toInstant(), window.start());
    }

    @Test
    void activeWindowIsInclusiveAtStartAndExclusiveAtEnd() {
        Instant start = Instant.parse("2026-08-15T21:00:00Z");
        Instant end = start.plus(Duration.ofHours(4));
        assertTrue(WarSchedule.active(start, start, end));
        assertTrue(WarSchedule.active(end.minusNanos(1), start, end));
        assertFalse(WarSchedule.active(end, start, end));
    }

    @Test
    void namedZoneTracksDaylightSavingInsteadOfFixedOffset() {
        Instant winterDeclaration = ZonedDateTime.of(2026, 11, 5, 12, 0, 0, 0, LOS_ANGELES).toInstant();
        WarSchedule.Window winter = WarSchedule.nextWindow(winterDeclaration, LOS_ANGELES, DayOfWeek.SATURDAY,
            LocalTime.of(14, 0), LocalTime.of(18, 0), Duration.ofHours(24));
        Instant summerDeclaration = ZonedDateTime.of(2026, 8, 13, 12, 0, 0, 0, LOS_ANGELES).toInstant();
        WarSchedule.Window summer = WarSchedule.nextWindow(summerDeclaration, LOS_ANGELES, DayOfWeek.SATURDAY,
            LocalTime.of(14, 0), LocalTime.of(18, 0), Duration.ofHours(24));
        assertEquals(21, summer.start().atZone(ZoneId.of("UTC")).getHour());
        assertEquals(22, winter.start().atZone(ZoneId.of("UTC")).getHour());
    }
}
