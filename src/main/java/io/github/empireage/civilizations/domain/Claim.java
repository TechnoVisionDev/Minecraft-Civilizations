package io.github.empireage.civilizations.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record Claim(
    long id,
    ChunkKey key,
    String worldName,
    long civilizationId,
    PlotType plotType,
    UUID plotOwnerId,
    String listingKind,
    UUID listingSellerId,
    BigDecimal listingPrice,
    BigDecimal originalPurchasePrice,
    Map<String, Boolean> flags,
    String homeLabel,
    String greeting,
    ClaimSource source,
    Map<ResourceKey, Long> claimCost,
    Instant claimedAt,
    UUID claimedBy,
    Set<UUID> trustedPlayers,
    long rowVersion
) {
    public boolean isCapital() {
        return plotType == PlotType.CAPITAL;
    }

    public boolean privatePlot() {
        return plotType == PlotType.PRIVATE;
    }
}
