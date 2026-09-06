package io.github.empireage.civilizations.config;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReligionCatalogTest {
    @Test
    void packagedPantheonDefinesOfferingsAndBlessings() {
        ReligionCatalog catalog = ReligionCatalog.load(new File("src/main/resources/religion.yml"));

        assertEquals(Duration.ofHours(4), catalog.cooldown());
        assertEquals(Duration.ofMinutes(30), catalog.buffDuration());
        assertEquals(6, catalog.gods().size());
        assertEquals(Material.COD, catalog.get("poseidon").offering());
        assertEquals(Material.WHEAT, catalog.get("DEMETER").offering());
        assertFalse(catalog.gods().values().stream().anyMatch(god -> god.buffs().isEmpty()));
    }
}
