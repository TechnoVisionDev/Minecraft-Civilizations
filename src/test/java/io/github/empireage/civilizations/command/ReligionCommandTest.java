package io.github.empireage.civilizations.command;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReligionCommandTest {
    @Test
    void offeringMustMatchAndStrictlyExceedTheDivineRoll() {
        assertTrue(ReligionCommand.qualifies(Material.COD, 32, Material.COD, 31));
        assertFalse(ReligionCommand.qualifies(Material.COD, 32, Material.COD, 32));
        assertFalse(ReligionCommand.qualifies(Material.SALMON, 64, Material.COD, 1));
    }
}
