package io.github.empireage.civilizations.concurrent;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

public final class CivilizationLocks {
    private final ReentrantLock[] stripes;

    public CivilizationLocks(int stripeCount) {
        if (stripeCount < 2) throw new IllegalArgumentException("stripeCount must be at least two");
        stripes = new ReentrantLock[stripeCount];
        Arrays.setAll(stripes, ignored -> new ReentrantLock());
    }

    public <T> T withLock(long civilizationId, CheckedSupplier<T> work) throws Exception {
        ReentrantLock lock = stripe(civilizationId);
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    public <T> T withLocks(long firstCivilizationId, long secondCivilizationId, CheckedSupplier<T> work) throws Exception {
        int firstIndex = stripeIndex(firstCivilizationId);
        int secondIndex = stripeIndex(secondCivilizationId);
        if (firstIndex == secondIndex) return withLock(firstCivilizationId, work);
        // Ordering by civilization ID is insufficient because IDs are mapped onto
        // stripes. Every caller must acquire in stripe-index order to prevent cycles.
        ReentrantLock first = stripes[Math.min(firstIndex, secondIndex)];
        ReentrantLock second = stripes[Math.max(firstIndex, secondIndex)];
        first.lock();
        second.lock();
        try {
            return work.get();
        } finally {
            second.unlock();
            first.unlock();
        }
    }

    private ReentrantLock stripe(long id) {
        return stripes[stripeIndex(id)];
    }

    private int stripeIndex(long id) {
        return Math.floorMod(Long.hashCode(id), stripes.length);
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
