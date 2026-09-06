package io.github.empireage.civilizations.service.territory;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, single-use confirmations.  A token is bound to the player, action,
 * and a fingerprint of the state the player was shown (normally a row version and
 * price).  Consequently a bare "confirm" can never approve another operation and
 * a changed listing invalidates the old confirmation.
 */
public final class ConfirmationTokens {
    private static final int TOKEN_BYTES = 12;

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Duration lifetime;

    public ConfirmationTokens(Clock clock, Duration lifetime) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("confirmation lifetime must be positive");
        }
    }

    public Confirmation issue(UUID actor, String action, String fingerprint) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(fingerprint, "fingerprint");
        purgeExpired();
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        Instant expiresAt = clock.instant().plus(lifetime);
        pending.put(token, new Pending(actor, action, fingerprint, expiresAt));
        return new Confirmation(token, expiresAt);
    }

    public boolean consume(UUID actor, String action, String fingerprint, String token) {
        if (actor == null || action == null || fingerprint == null || token == null) return false;
        Pending value = pending.remove(token);
        return value != null
            && clock.instant().isBefore(value.expiresAt())
            && value.actor().equals(actor)
            && value.action().equals(action)
            && value.fingerprint().equals(fingerprint);
    }

    public void invalidate(UUID actor, String action) {
        pending.entrySet().removeIf(entry -> entry.getValue().actor().equals(actor)
            && entry.getValue().action().equals(action));
    }

    public int purgeExpired() {
        Instant now = clock.instant();
        int before = pending.size();
        pending.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
        return before - pending.size();
    }

    public record Confirmation(String token, Instant expiresAt) {}

    private record Pending(UUID actor, String action, String fingerprint, Instant expiresAt) {}
}
