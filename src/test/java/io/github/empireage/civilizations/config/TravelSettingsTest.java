package io.github.empireage.civilizations.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TravelSettingsTest {
    @Test
    void packagedDefaultsUseConventionalDimensionWorldsAndWorldSpawns() {
        Settings settings = Settings.load(YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")));

        assertEquals(Duration.ofSeconds(5), settings.travel().warmup());
        assertEquals(Duration.ofSeconds(60), settings.travel().cooldown());
        assertEquals(8, settings.travel().safeSearchRadius());
        assertEquals(1, settings.travel().claimProtectionRadiusChunks());
        assertEquals("world", settings.travel().overworld().world());
        assertEquals("world_nether", settings.travel().nether().world());
        assertEquals("world_the_end", settings.travel().end().world());
        assertTrue(settings.travel().overworld().usesWorldSpawn());
        assertTrue(settings.travel().nether().usesWorldSpawn());
        assertTrue(settings.travel().end().usesWorldSpawn());
    }

    @Test
    void customDestinationCoordinatesMustBeComplete() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        config.set("travel.destinations.nether.x", 12.5D);

        assertThrows(IllegalArgumentException.class, () -> Settings.load(config));
    }
}
