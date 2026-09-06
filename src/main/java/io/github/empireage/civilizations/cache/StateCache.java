package io.github.empireage.civilizations.cache;

import io.github.empireage.civilizations.database.Database;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class StateCache {
    private final Database database;
    private final StateRepository repository;
    private final AtomicReference<StateSnapshot> snapshot = new AtomicReference<>(StateSnapshot.empty());
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicLong invalidationGeneration = new AtomicLong();
    private CompletableFuture<StateSnapshot> refreshTail = CompletableFuture.completedFuture(StateSnapshot.empty());

    public StateCache(Database database, StateRepository repository) {
        this.database = database;
        this.repository = repository;
    }

    public StateSnapshot snapshot() {
        return snapshot.get();
    }

    public boolean ready() {
        return ready.get();
    }

    public synchronized CompletableFuture<StateSnapshot> refresh() {
        long requestedGeneration = invalidationGeneration.get();
        refreshTail = refreshTail.handle((ignored, error) -> null)
            .thenCompose(ignored -> database.consistentRead(repository::load))
            .thenApply(loaded -> {
                snapshot.set(loaded);
                synchronized (StateCache.this) {
                    if (invalidationGeneration.get() == requestedGeneration) ready.set(true);
                }
                return loaded;
            });
        return refreshTail;
    }

    /**
     * Invalidates authorization immediately after an authoritative mutation,
     * then publishes one coherent replacement snapshot.
     */
    public synchronized CompletableFuture<StateSnapshot> refreshAfterMutation() {
        invalidationGeneration.incrementAndGet();
        ready.set(false);
        return refresh();
    }

    public synchronized void markNotReady() {
        invalidationGeneration.incrementAndGet();
        ready.set(false);
    }
}
