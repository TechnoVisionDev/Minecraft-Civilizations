package io.github.empireage.civilizations.command;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Command-facing aliases for opaque service tokens and command-owned confirmations. */
final class CommandConfirmations {
    private final Clock clock;
    private final ConcurrentHashMap<Key, Pending> pending = new ConcurrentHashMap<>();

    CommandConfirmations(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    void remember(UUID playerId, String action, String value, Instant expiresAt) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(expiresAt, "expiresAt");
        purgeExpired();
        pending.put(new Key(playerId, action), new Pending(value, expiresAt));
    }

    String consume(UUID playerId, String action) {
        Pending value = pending.remove(new Key(playerId, action));
        return value != null && clock.instant().isBefore(value.expiresAt()) ? value.value() : null;
    }

    boolean has(UUID playerId, String action) {
        Pending value = pending.get(new Key(playerId, action));
        if (value == null) return false;
        if (clock.instant().isBefore(value.expiresAt())) return true;
        pending.remove(new Key(playerId, action), value);
        return false;
    }

    void invalidate(UUID playerId, String action) {
        pending.remove(new Key(playerId, action));
    }

    int purgeExpired() {
        Instant now = clock.instant();
        int before = pending.size();
        pending.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
        return before - pending.size();
    }

    private record Key(UUID playerId, String action) {}
    private record Pending(String value, Instant expiresAt) {}
}
