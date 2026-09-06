package io.github.empireage.civilizations.service.lifecycle;

import io.github.empireage.civilizations.domain.ResourceKey;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoundingInventoryTest {
    private static final ResourceKey MASONRY = ResourceKey.parse("masonry:1");
    private static final ResourceKey TIMBER = ResourceKey.parse("timber:1");

    @Test
    void withdrawProducesExactRemovalAndNewImmutableSnapshot() {
        FoundingInventory inventory = new FoundingInventory(Map.of(MASONRY, 7L, TIMBER, 2L));

        FoundingInventory.Withdrawal withdrawal = inventory.withdraw(Map.of(MASONRY, 4L, TIMBER, 2L));

        assertEquals(Map.of(MASONRY, 4L, TIMBER, 2L), withdrawal.removed());
        assertEquals(3, withdrawal.remaining().quantity(MASONRY));
        assertEquals(0, withdrawal.remaining().quantity(TIMBER));
        assertEquals(7, inventory.quantity(MASONRY), "the captured inventory view must not be mutated");
        assertThrows(UnsupportedOperationException.class, () -> withdrawal.removed().put(MASONRY, 99L));
    }

    @Test
    void shortfallReportsOnlyMissingAmounts() {
        FoundingInventory inventory = new FoundingInventory(Map.of(MASONRY, 1L));

        assertEquals(Map.of(MASONRY, 3L, TIMBER, 2L), inventory.shortfall(Map.of(MASONRY, 4L, TIMBER, 2L)));
        FoundingInventory.InsufficientFoundingMaterialsException failure = assertThrows(
            FoundingInventory.InsufficientFoundingMaterialsException.class,
            () -> inventory.withdraw(Map.of(MASONRY, 4L, TIMBER, 2L)));
        assertEquals(Map.of(MASONRY, 3L, TIMBER, 2L), failure.shortfall());
    }

    @Test
    void zeroCostsAreIgnoredAndAvailabilityIsDeterministic() {
        FoundingInventory inventory = new FoundingInventory(Map.of(MASONRY, 4L));

        assertTrue(inventory.canWithdraw(Map.of(MASONRY, 4L, TIMBER, 0L)));
        assertFalse(inventory.canWithdraw(Map.of(MASONRY, 5L)));
        assertEquals(Map.of(MASONRY, 4L), inventory.withdraw(Map.of()).remaining().quantities());
    }

    @Test
    void constructorDefensivelyCopiesAndRejectsNegativeValues() {
        Map<ResourceKey, Long> mutable = new LinkedHashMap<>();
        mutable.put(MASONRY, 4L);
        FoundingInventory inventory = new FoundingInventory(mutable);
        mutable.put(MASONRY, 1L);

        assertEquals(4, inventory.quantity(MASONRY));
        assertThrows(IllegalArgumentException.class, () -> new FoundingInventory(Map.of(MASONRY, -1L)));
        assertThrows(IllegalArgumentException.class, () -> inventory.withdraw(Map.of(MASONRY, -1L)));
    }
}
