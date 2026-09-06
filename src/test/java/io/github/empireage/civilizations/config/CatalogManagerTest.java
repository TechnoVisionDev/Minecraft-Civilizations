package io.github.empireage.civilizations.config;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogManagerTest {
    @TempDir
    Path dataDirectory;

    @Test
    void legacyDepositCatalogIsBackedUpAndReplacedBeforeStartupContinues() throws Exception {
        copyPackaged("resources.yml");
        copyPackaged("technologies.yml");
        Files.writeString(dataDirectory.resolve("workorders.yml"), """
            cooldown: 24h
            templates:
              deposit_masonry:
                name: Deposit Masonry
                category: DEPOSIT
                civic-resource: masonry:1
                target: 10
                reward: 1
            """);

        CatalogManager catalogs = new CatalogManager(plugin());
        catalogs.load();

        assertEquals(5, catalogs.workOrders().category(WorkOrderCatalog.Category.BASIC).size());
        assertEquals(6, catalogs.workOrders().category(WorkOrderCatalog.Category.INDUSTRIAL).size());
        assertEquals(6, catalogs.workOrders().category(WorkOrderCatalog.Category.STRATEGIC).size());
        assertTrue(Files.readString(dataDirectory.resolve("workorders.yml.pre-v3.bak")).contains("DEPOSIT"));
        assertTrue(Files.readString(dataDirectory.resolve("workorders.yml")).contains("category: BASIC"));
    }

    @Test
    void preFinalTechnologyCatalogIsBackedUpAndOldTechnologiesAreRemoved() throws Exception {
        copyPackaged("resources.yml");
        copyPackaged("workorders.yml");
        Files.writeString(dataDirectory.resolve("technologies.yml"), """
            technologies:
              siegecraft:
                name: Siegecraft
                prerequisites: []
                knowledge: 1
                duration: 1h
                capabilities: [DECLARE_WAR]
            """);

        CatalogManager catalogs = new CatalogManager(plugin());
        catalogs.load();

        assertEquals(26, catalogs.technologies().technologies().size());
        assertTrue(catalogs.technologies().get("siegecraft") == null);
        assertTrue(Files.readString(dataDirectory.resolve("technologies.yml.pre-v3.bak")).contains("siegecraft"));
        assertTrue(Files.readString(dataDirectory.resolve("technologies.yml")).contains("catalog-version: 3"));
    }

    private JavaPlugin plugin() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CatalogManagerTest"));
        doAnswer(invocation -> {
            String resource = invocation.getArgument(0);
            Files.copy(Path.of("src/main/resources").resolve(resource), dataDirectory.resolve(resource),
                StandardCopyOption.REPLACE_EXISTING);
            return null;
        }).when(plugin).saveResource(anyString(), anyBoolean());
        return plugin;
    }

    private void copyPackaged(String name) throws Exception {
        Files.copy(Path.of("src/main/resources").resolve(name), dataDirectory.resolve(name));
    }
}
