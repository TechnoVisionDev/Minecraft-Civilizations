package io.github.empireage.civilizations.service.lifecycle;

import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.CivilizationStatus;
import io.github.empireage.civilizations.domain.HomeLocation;
import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.domain.ResourceKey;
import io.github.empireage.civilizations.domain.Role;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class LifecycleModels {
    private LifecycleModels() {}

    public record CreateRequest(
        UUID founderId,
        String founderName,
        String civilizationName,
        ChunkKey capitalChunk,
        String worldName,
        String biomeKey,
        HomeLocation home,
        boolean externallyProtected,
        FoundingInventory inventory,
        BigDecimal availableMoney,
        UUID foundingOperationId
    ) {
        public CreateRequest {
            Objects.requireNonNull(founderId, "founderId");
            Objects.requireNonNull(founderName, "founderName");
            Objects.requireNonNull(capitalChunk, "capitalChunk");
            Objects.requireNonNull(worldName, "worldName");
            Objects.requireNonNull(home, "home");
            Objects.requireNonNull(inventory, "inventory");
            Objects.requireNonNull(availableMoney, "availableMoney");
        }
    }

    public record CreateResult(
        OperationResult result,
        Long civilizationId,
        Map<ResourceKey, Long> materialsToRemove,
        FoundingInventory inventoryAfterRemoval,
        BigDecimal moneyToRemove
    ) {
        public CreateResult {
            Objects.requireNonNull(result, "result");
            materialsToRemove = Map.copyOf(Objects.requireNonNull(materialsToRemove, "materialsToRemove"));
            Objects.requireNonNull(inventoryAfterRemoval, "inventoryAfterRemoval");
            Objects.requireNonNull(moneyToRemove, "moneyToRemove");
            if (result.success() != (civilizationId != null)) {
                throw new IllegalArgumentException("Successful creation results must carry a civilization id");
            }
        }

        public static CreateResult denied(String message, FoundingInventory inventory) {
            return new CreateResult(OperationResult.denied(message), null, Map.of(), inventory, BigDecimal.ZERO.setScale(2));
        }
    }

    public record MemberInfo(UUID playerId, String lastKnownName, Role role, Instant joinedAt,
                             Instant lastActiveAt, long activeWindowSeconds, boolean established,
                             long contributionTotal, boolean membershipLocked) {}

    public record CivilizationInfo(
        long id,
        String name,
        CivilizationStatus status,
        UUID leaderId,
        Instant createdAt,
        Instant peaceShieldUntil,
        Long currentWarId,
        int claimCount,
        int claimCapacity,
        int establishedNonLeaders,
        int advisorCount,
        int advisorLimit,
        BigDecimal treasury,
        long knowledge,
        List<MemberInfo> members
    ) {
        public CivilizationInfo {
            members = List.copyOf(members);
        }
    }

    public record CivilizationListEntry(long id, String name, int members, int claims,
                                        Instant createdAt, boolean atWar) {}

    public record InvitationInfo(long id, long civilizationId, String civilizationName,
                                 UUID targetId, String targetName, UUID inviterId, Instant createdAt,
                                 Instant expiresAt) {}

    public record MembershipCooldown(boolean active, Instant until, Duration remaining) {
        public MembershipCooldown {
            Objects.requireNonNull(remaining, "remaining");
            if (active != (until != null)) throw new IllegalArgumentException("Active cooldowns must have an expiry");
        }

        public static MembershipCooldown none() {
            return new MembershipCooldown(false, null, Duration.ZERO);
        }
    }

    public record ActivityUpdate(UUID playerId, String lastKnownName, Instant observedSince,
                                 Instant observedAt) {
        public ActivityUpdate {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(lastKnownName, "lastKnownName");
            Objects.requireNonNull(observedSince, "observedSince");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    public record ActivityResult(OperationResult result, boolean established, boolean establishmentChanged,
                                 Instant lastActiveAt, long activeSecondsInWindow) {}

    public record ChatPreference(UUID playerId, boolean civilizationChat, String lastKnownName, Instant updatedAt) {}

    public record DisbandResult(OperationResult result, Long civilizationId, BigDecimal treasuryPayout,
                                UUID payoutRecoveryOperationId) {
        public DisbandResult {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(treasuryPayout, "treasuryPayout");
        }

        public static DisbandResult denied(String message) {
            return new DisbandResult(OperationResult.denied(message), null, BigDecimal.ZERO.setScale(2), null);
        }
    }
}
