package io.github.empireage.civilizations.service.progression;

import io.github.empireage.civilizations.config.WorkOrderCatalog;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkOrderScheduleTest {
    private final WorkOrderCatalog catalog = WorkOrderCatalog.load(new File("src/main/resources/workorders.yml"));

    @Test
    void selectionStaysInCategoryAndIsStableForASeed() {
        WorkOrderCatalog.Template first = WorkOrderSchedule.select(catalog, WorkOrderCatalog.Category.BASIC,
            Set.of(), List.of(), 42);
        WorkOrderCatalog.Template repeated = WorkOrderSchedule.select(catalog, WorkOrderCatalog.Category.BASIC,
            Set.of(), List.of(), 42);

        assertEquals(first.key(), repeated.key());
        assertEquals(WorkOrderCatalog.Category.BASIC, first.category());
    }

    @Test
    void rotationAvoidsThePreviousTwoAndRespectsEligibility() {
        WorkOrderCatalog.Template selected = WorkOrderSchedule.select(catalog, WorkOrderCatalog.Category.STRATEGIC,
            Set.of(), List.of("strategic_lapis", "strategic_redstone"), 7);

        assertNotEquals("strategic_lapis", selected.key());
        assertNotEquals("strategic_redstone", selected.key());
        assertTrue(selected.requiredTechnologies().isEmpty());
    }
}
