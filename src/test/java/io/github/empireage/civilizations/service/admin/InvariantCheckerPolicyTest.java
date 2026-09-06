package io.github.empireage.civilizations.service.admin;

import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.WarState;
import io.github.empireage.civilizations.service.admin.InvariantModels.Kind;
import io.github.empireage.civilizations.service.admin.InvariantModels.Violation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvariantCheckerPolicyTest {
    @Test
    void findsDisconnectedClaimsAndInvalidPrivateOwner() {
        UUID world = UUID.randomUUID();
        UUID plotOwner = UUID.randomUUID();
        var civilization = new InvariantChecker.CivilizationData(1, "ACTIVE", new ChunkKey(world, 0, 0), null);
        var capital = new InvariantChecker.ClaimData(10, 1, new ChunkKey(world, 0, 0), "CAPITAL", null);
        var disconnectedPrivate = new InvariantChecker.ClaimData(11, 1, new ChunkKey(world, 5, 5), "PRIVATE", plotOwner);
        List<Violation> violations = new ArrayList<>();

        InvariantChecker.checkClaims(Map.of(1L, civilization), Map.of(1L, List.of(capital, disconnectedPrivate)),
            Map.of(plotOwner, 2L), violations);

        assertEquals(2, violations.size());
        assertTrue(violations.stream().anyMatch(value -> value.kind() == Kind.DISCONNECTED_CLAIMS));
        assertTrue(violations.stream().anyMatch(value -> value.kind() == Kind.INVALID_PLOT_OWNER));
    }

    @Test
    void findsMultipleCampaignsAndPointerMismatch() {
        UUID world = UUID.randomUUID();
        Map<Long, InvariantChecker.CivilizationData> civilizations = Map.of(
            1L, new InvariantChecker.CivilizationData(1, "ACTIVE", new ChunkKey(world, 0, 0), 100L),
            2L, new InvariantChecker.CivilizationData(2, "ACTIVE", new ChunkKey(world, 1, 0), 100L),
            3L, new InvariantChecker.CivilizationData(3, "ACTIVE", new ChunkKey(world, 2, 0), 101L));
        List<InvariantChecker.WarData> wars = List.of(
            new InvariantChecker.WarData(100, 1, 2, WarState.PENDING),
            new InvariantChecker.WarData(101, 1, 3, WarState.ACTIVE));
        List<Violation> violations = new ArrayList<>();

        InvariantChecker.checkWars(civilizations, wars, violations);

        assertTrue(violations.stream().anyMatch(value -> value.kind() == Kind.MULTIPLE_CURRENT_WARS
            && value.identity().equals("1")));
        assertTrue(violations.stream().anyMatch(value -> value.kind() == Kind.WAR_POINTER_MISMATCH
            && value.identity().equals("1")));
    }
}
