package io.github.empireage.civilizations.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

public record War(
    long id,
    long attackerCivilizationId,
    long defenderCivilizationId,
    WarState state,
    UUID declarationActor,
    Instant declaredAt,
    Instant scheduledStart,
    Instant scheduledEnd,
    ZoneId zoneId,
    Instant cancellationGraceUntil,
    Instant objectiveLockAt,
    Instant truceUntil,
    boolean attackerPeace,
    boolean defenderPeace,
    Set<UUID> attackerRoster,
    Set<UUID> defenderRoster
) {
    public WarState effectiveState(Instant now) {
        if (state == WarState.CANCELLED || state == WarState.COMPLETED || state == WarState.TRUCE) return state;
        if (!now.isBefore(scheduledEnd)) return WarState.RESOLVING;
        if (!now.isBefore(scheduledStart)) return WarState.ACTIVE;
        return WarState.PENDING;
    }

    public Long opponent(long civilizationId) {
        if (civilizationId == attackerCivilizationId) return defenderCivilizationId;
        if (civilizationId == defenderCivilizationId) return attackerCivilizationId;
        return null;
    }

    public boolean rostered(long civilizationId, UUID playerId) {
        return civilizationId == attackerCivilizationId ? attackerRoster.contains(playerId)
            : civilizationId == defenderCivilizationId && defenderRoster.contains(playerId);
    }
}
