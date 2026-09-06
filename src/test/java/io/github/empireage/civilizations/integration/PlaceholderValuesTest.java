package io.github.empireage.civilizations.integration;

import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.config.TechnologyCatalog;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.Civilization;
import io.github.empireage.civilizations.domain.CivilizationStatus;
import io.github.empireage.civilizations.domain.HomeLocation;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.Role;
import io.github.empireage.civilizations.domain.War;
import io.github.empireage.civilizations.domain.WarState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaceholderValuesTest {
    @Test
    void formatsDurationsCompactlyAndNeverNegative() {
        assertEquals("2d 3h 4m", PlaceholderValues.formatDuration(
            Duration.ofDays(2).plusHours(3).plusMinutes(4).plusSeconds(5)));
        assertEquals("19s", PlaceholderValues.formatDuration(Duration.ofSeconds(19)));
        assertEquals("0s", PlaceholderValues.formatDuration(Duration.ofSeconds(-5)));
    }

    @Test
    void resolvesCurrentTruceEvenThoughItIsNotACurrentWarPointer() {
        Instant now = Instant.parse("2026-08-17T12:00:00Z");
        UUID world = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        Civilization first = civilization(1, "Avalon", player, new ChunkKey(world, 0, 0), now);
        Civilization second = civilization(2, "Byzantium", UUID.randomUUID(), new ChunkKey(world, 1, 0), now);
        Member member = new Member(1, player, "Tester", Role.LEADER, now, now, 0, false, 0, false);
        War truce = new War(9, 1, 2, WarState.TRUCE, player, now.minus(Duration.ofDays(1)),
            now.minus(Duration.ofHours(5)), now.minus(Duration.ofHours(1)), ZoneId.of("UTC"), now, now,
            now.plus(Duration.ofHours(2)), false, false, Set.of(player), Set.of());
        StateSnapshot snapshot = new StateSnapshot(Map.of(1L, first, 2L, second),
            Map.of("avalon", 1L, "byzantium", 2L),
            Map.of(player, member), Map.of(1L, java.util.List.of(member)), Map.of(), Map.of(), Map.of(), Map.of(),
            Map.of(9L, truce), Map.of(), Map.of(), now);
        Settings.Claims claims = new Settings.Claims(8, 2, 128, 50, Duration.ofDays(7), Map.of());
        PlaceholderValues values = new PlaceholderValues(() -> snapshot, claims, new TechnologyCatalog(Map.of()),
            Clock.fixed(now, ZoneOffset.UTC));

        assertEquals("Avalon", values.resolve(player, "name"));
        assertEquals("LEADER", values.resolve(player, "role"));
        assertEquals("TRUCE", values.resolve(player, "war_state"));
        assertEquals("Byzantium", values.resolve(player, "war_enemy"));
        assertEquals("2h", values.resolve(player, "war_remaining"));
    }

    private Civilization civilization(long id, String name, UUID leader, ChunkKey capital, Instant now) {
        return new Civilization(id, name, leader, CivilizationStatus.ACTIVE, capital, "world",
            new HomeLocation(capital.worldId(), 0, 64, 0, 0, 0), BigDecimal.ZERO, BigDecimal.ZERO, 0,
            null, null, 0, null, now, 0);
    }
}
