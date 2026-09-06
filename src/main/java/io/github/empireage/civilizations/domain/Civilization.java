package io.github.empireage.civilizations.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Civilization(
    long id,
    String name,
    UUID leaderId,
    CivilizationStatus status,
    ChunkKey capital,
    String capitalWorldName,
    HomeLocation home,
    BigDecimal defaultPlotPrice,
    BigDecimal treasury,
    long knowledge,
    Instant peaceShieldUntil,
    Long currentWarId,
    int adminClaimBonus,
    Instant capitalMovedAt,
    Instant createdAt,
    long rowVersion
) {
    public Civilization {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(leaderId, "leaderId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(capital, "capital");
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(defaultPlotPrice, "defaultPlotPrice");
        Objects.requireNonNull(treasury, "treasury");
    }

    public boolean warLocked() {
        return currentWarId != null;
    }
}
