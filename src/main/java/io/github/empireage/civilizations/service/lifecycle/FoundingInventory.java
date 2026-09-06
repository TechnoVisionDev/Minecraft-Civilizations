package io.github.empireage.civilizations.service.lifecycle;

import io.github.empireage.civilizations.domain.ResourceKey;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable, Bukkit-free view of the founder's civic-item inventory.
 *
 * <p>The command layer captures this view on the server thread. A successful
 * creation result exposes the exact withdrawal and the resulting view so the
 * command layer can remove those stacks without accessing Bukkit inventory
 * objects from a database worker.</p>
 */
public record FoundingInventory(Map<ResourceKey, Long> quantities) {
    public FoundingInventory {
        Objects.requireNonNull(quantities, "quantities");
        Map<ResourceKey, Long> copy = new LinkedHashMap<>();
        quantities.forEach((key, quantity) -> {
            Objects.requireNonNull(key, "resource key");
            Objects.requireNonNull(quantity, "resource quantity");
            if (quantity < 0) throw new IllegalArgumentException("Inventory quantities cannot be negative");
            if (quantity > 0) copy.merge(key, quantity, Math::addExact);
        });
        quantities = Map.copyOf(copy);
    }

    public static FoundingInventory empty() {
        return new FoundingInventory(Map.of());
    }

    public long quantity(ResourceKey key) {
        return quantities.getOrDefault(Objects.requireNonNull(key, "key"), 0L);
    }

    public Map<ResourceKey, Long> shortfall(Map<ResourceKey, Long> cost) {
        Map<ResourceKey, Long> missing = new LinkedHashMap<>();
        validatedCost(cost).forEach((key, required) -> {
            long deficit = required - quantity(key);
            if (deficit > 0) missing.put(key, deficit);
        });
        return Map.copyOf(missing);
    }

    public boolean canWithdraw(Map<ResourceKey, Long> cost) {
        return shortfall(cost).isEmpty();
    }

    public Withdrawal withdraw(Map<ResourceKey, Long> cost) {
        Map<ResourceKey, Long> normalizedCost = validatedCost(cost);
        Map<ResourceKey, Long> missing = shortfall(normalizedCost);
        if (!missing.isEmpty()) throw new InsufficientFoundingMaterialsException(missing);

        Map<ResourceKey, Long> remaining = new LinkedHashMap<>(quantities);
        normalizedCost.forEach((key, required) -> {
            long value = remaining.getOrDefault(key, 0L) - required;
            if (value == 0) remaining.remove(key); else remaining.put(key, value);
        });
        return new Withdrawal(normalizedCost, new FoundingInventory(remaining));
    }

    private static Map<ResourceKey, Long> validatedCost(Map<ResourceKey, Long> cost) {
        Objects.requireNonNull(cost, "cost");
        Map<ResourceKey, Long> copy = new LinkedHashMap<>();
        cost.forEach((key, quantity) -> {
            Objects.requireNonNull(key, "cost resource key");
            Objects.requireNonNull(quantity, "cost quantity");
            if (quantity < 0) throw new IllegalArgumentException("Founding costs cannot be negative");
            if (quantity > 0) copy.merge(key, quantity, Math::addExact);
        });
        return Map.copyOf(copy);
    }

    public record Withdrawal(Map<ResourceKey, Long> removed, FoundingInventory remaining) {
        public Withdrawal {
            removed = Map.copyOf(Objects.requireNonNull(removed, "removed"));
            Objects.requireNonNull(remaining, "remaining");
        }
    }

    public static final class InsufficientFoundingMaterialsException extends IllegalArgumentException {
        private final Map<ResourceKey, Long> shortfall;

        public InsufficientFoundingMaterialsException(Map<ResourceKey, Long> shortfall) {
            super("Required founding materials are missing");
            this.shortfall = Map.copyOf(shortfall);
        }

        public Map<ResourceKey, Long> shortfall() {
            return shortfall;
        }
    }
}
