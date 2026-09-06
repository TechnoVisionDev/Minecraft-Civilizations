package io.github.empireage.civilizations.domain;

import java.time.Instant;
import java.util.UUID;

public record Member(
    long civilizationId,
    UUID playerId,
    String lastKnownName,
    Role role,
    Instant joinedAt,
    Instant lastActiveAt,
    long eligiblePlaytimeSeconds,
    boolean established,
    long contributionTotal,
    boolean membershipLocked
) {}
