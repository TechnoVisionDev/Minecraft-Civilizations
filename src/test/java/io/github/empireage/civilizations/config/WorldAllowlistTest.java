package io.github.empireage.civilizations.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldAllowlistTest {
    @Test
    void defaultStyleAllowlistPermitsOnlyTheNamedOverworld() {
        Settings.Worlds worlds = new Settings.Worlds(Set.of("world"), Set.of(), true);

        assertTrue(worlds.allowed("world"));
        assertTrue(worlds.allowed("WORLD"));
        assertFalse(worlds.allowed("world_nether"));
        assertFalse(worlds.allowed("world_the_end"));
    }

    @Test
    void additionalWorldsCanBeEnabledExplicitly() {
        Settings.Worlds worlds = new Settings.Worlds(Set.of("world", "building_world"), Set.of(), true);

        assertTrue(worlds.allowed("building_world"));
        assertFalse(worlds.allowed("unlisted_world"));
    }
}
