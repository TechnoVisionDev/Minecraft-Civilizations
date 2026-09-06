package io.github.empireage.civilizations.service.war;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WarRuntimeTest {
    @Test
    void cleanupRestoresOnlyTheExactPersistedPlacement() {
        String placed = "minecraft:oak_planks[axis=y]";

        assertTrue(WarRuntime.unchanged(placed, placed));
        assertFalse(WarRuntime.unchanged("minecraft:oak_planks[axis=x]", placed));
        assertFalse(WarRuntime.unchanged("minecraft:chest[facing=north,type=single,waterlogged=false]", placed));
        assertFalse(WarRuntime.unchanged(null, placed));
    }
}
