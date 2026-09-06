package io.github.empireage.civilizations.service.admin;

import io.github.empireage.civilizations.domain.ChunkKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminRepairPolicyTest {
    @Test
    void forceUnclaimReportsEveryDestructiveSideEffect() {
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ChunkKey capital = new ChunkKey(world, 0, 0);
        ChunkKey bridge = new ChunkKey(world, 1, 0);
        ChunkKey far = new ChunkKey(world, 2, 0);
        var civilization = new AdminService.CivilizationRow(7, UUID.randomUUID(), capital, 42L, 0, 0);
        var claim = new AdminService.ClaimRow(11, 7, "PRIVATE", owner, "CIV_SALE");

        List<String> warnings = AdminService.persistedUnclaimWarnings(
            List.of(capital, bridge, far), civilization, bridge, claim);

        assertEquals(4, warnings.size());
        assertTrue(warnings.stream().anyMatch(value -> value.contains("disconnects")));
        assertTrue(warnings.stream().anyMatch(value -> value.contains("private plot")));
        assertTrue(warnings.stream().anyMatch(value -> value.contains("listing")));
        assertTrue(warnings.stream().anyMatch(value -> value.contains("campaign")));
    }

    @Test
    void harmlessLeafUnclaimNeedsNoWarning() {
        UUID world = UUID.randomUUID();
        ChunkKey capital = new ChunkKey(world, 0, 0);
        ChunkKey leaf = new ChunkKey(world, 1, 0);
        var civilization = new AdminService.CivilizationRow(7, UUID.randomUUID(), capital, null, 0, 0);
        var claim = new AdminService.ClaimRow(11, 7, "CIVIC", null, null);

        assertTrue(AdminService.persistedUnclaimWarnings(
            List.of(capital, leaf), civilization, leaf, claim).isEmpty());
    }
}
