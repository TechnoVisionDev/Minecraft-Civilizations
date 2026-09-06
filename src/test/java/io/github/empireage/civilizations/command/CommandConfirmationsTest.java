package io.github.empireage.civilizations.command;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandConfirmationsTest {
    @Test
    void confirmationIsBoundToPlayerAndActionAndConsumedOnce() {
        Instant now = Instant.parse("2026-08-17T20:00:00Z");
        CommandConfirmations confirmations = new CommandConfirmations(Clock.fixed(now, ZoneOffset.UTC));
        UUID player = UUID.randomUUID();
        confirmations.remember(player, "plot-buy", "opaque-token", now.plusSeconds(30));

        assertTrue(confirmations.has(player, "plot-buy"));
        assertNull(confirmations.consume(player, "plot-surrender"));
        assertEquals("opaque-token", confirmations.consume(player, "plot-buy"));
        assertNull(confirmations.consume(player, "plot-buy"));
    }

    @Test
    void expiredConfirmationCannotBeUsed() {
        Instant now = Instant.parse("2026-08-17T20:00:00Z");
        CommandConfirmations confirmations = new CommandConfirmations(Clock.fixed(now, ZoneOffset.UTC));
        UUID player = UUID.randomUUID();
        confirmations.remember(player, "unclaim", "opaque-token", now);

        assertFalse(confirmations.has(player, "unclaim"));
        assertNull(confirmations.consume(player, "unclaim"));
    }

    @Test
    void rememberingAgainInvalidatesOlderAlias() {
        Instant now = Instant.parse("2026-08-17T20:00:00Z");
        CommandConfirmations confirmations = new CommandConfirmations(Clock.fixed(now, ZoneOffset.UTC));
        UUID player = UUID.randomUUID();
        confirmations.remember(player, "setcapital", "first", now.plusSeconds(30));
        confirmations.remember(player, "setcapital", "second", now.plusSeconds(30));

        assertEquals("second", confirmations.consume(player, "setcapital"));
    }
}
