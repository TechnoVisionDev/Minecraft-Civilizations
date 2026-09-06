package io.github.empireage.civilizations.concurrent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class CivilizationLocksTest {
    @Test
    void overlappingPairsCannotDeadlockWhenCivilizationAndStripeOrdersDiffer() {
        CivilizationLocks locks = new CivilizationLocks(256);
        CountDownLatch start = new CountDownLatch(1);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            CompletableFuture<Void> one = CompletableFuture.runAsync(() -> lock(locks, 250, 257, start));
            CompletableFuture<Void> two = CompletableFuture.runAsync(() -> lock(locks, 1, 506, start));
            start.countDown();
            CompletableFuture.allOf(one, two).join();
        });
    }

    private static void lock(CivilizationLocks locks, long first, long second, CountDownLatch start) {
        try {
            start.await();
            locks.withLocks(first, second, () -> null);
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }
}
