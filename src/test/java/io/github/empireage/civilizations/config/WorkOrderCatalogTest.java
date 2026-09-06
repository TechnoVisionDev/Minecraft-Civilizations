package io.github.empireage.civilizations.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkOrderCatalogTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void legacyCategoriesAreInferredFromKeysAndAliases() throws Exception {
        File file = write("""
            cooldown: 24h
            templates:
              basic_one:
                name: Basic One
                materials: [STONE]
                target: 1
                reward: 1
              basic_two:
                name: Basic Two
                category: foundation
                materials: [DIRT]
                target: 1
                reward: 1
              industrial_one:
                name: Industrial One
                pool: industry
                materials: [IRON_INGOT]
                target: 1
                reward: 1
              industrial_two:
                name: Industrial Two
                category: INDUSTRIAL
                materials: [COPPER_INGOT]
                target: 1
                reward: 1
              strategic_one:
                name: Strategic One
                type: military
                materials: [REDSTONE]
                target: 1
                reward: 1
              strategic_two:
                name: Strategic Two
                category: STRATEGIC
                materials: [LAPIS_LAZULI]
                target: 1
                reward: 1
            """);

        WorkOrderCatalog catalog = WorkOrderCatalog.load(file);

        assertEquals(2, catalog.category(WorkOrderCatalog.Category.BASIC).size());
        assertEquals(2, catalog.category(WorkOrderCatalog.Category.INDUSTRIAL).size());
        assertEquals(2, catalog.category(WorkOrderCatalog.Category.STRATEGIC).size());
    }

    @Test
    void missingLegacyCategoryReportsTheAffectedTemplate() throws Exception {
        File file = write("""
            templates:
              old_supply_order:
                materials: [STONE]
                target: 1
                reward: 1
            """);

        WorkOrderCatalog.LegacyCatalogException failure = assertThrows(
            WorkOrderCatalog.LegacyCatalogException.class, () -> WorkOrderCatalog.load(file));

        assertTrue(failure.getMessage().contains("old_supply_order"));
    }

    @Test
    void obsoleteExplicitCategoryIsRecognizedAsALegacyCatalog() throws Exception {
        File file = write("""
            templates:
              deposit_masonry:
                category: DEPOSIT
                materials: [STONE]
                target: 1
                reward: 1
            """);

        WorkOrderCatalog.LegacyCatalogException failure = assertThrows(WorkOrderCatalog.LegacyCatalogException.class,
            () -> WorkOrderCatalog.load(file));

        assertTrue(failure.getMessage().contains("deposit_masonry"));
        assertTrue(failure.getMessage().contains("DEPOSIT"));
    }

    private File write(String contents) throws Exception {
        Path file = temporaryDirectory.resolve("workorders.yml");
        Files.writeString(file, contents);
        return file.toFile();
    }
}
