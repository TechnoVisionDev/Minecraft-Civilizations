package io.github.empireage.civilizations.service.lifecycle;

import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.util.NameNormalizer;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/** Pure lifecycle rules shared by command prevalidation and transactional checks. */
public final class LifecyclePolicy {
    private static final int MAX_CLOCK_SKEW_SECONDS = 300;

    private LifecyclePolicy() {}

    public static IdentityValidation validateIdentity(String rawName) {
        if (rawName == null) return IdentityValidation.denied("A civilization name is required.");

        String name = Normalizer.normalize(rawName, Normalizer.Form.NFKC).strip().replaceAll(" +", " ");
        int nameLength = name.codePointCount(0, name.length());
        if (nameLength < 3 || nameLength > 24) {
            return IdentityValidation.denied("Civilization names must contain 3 to 24 visible characters.");
        }
        if (!name.codePoints().allMatch(LifecyclePolicy::allowedNameCodePoint)) {
            return IdentityValidation.denied("Civilization names may contain only letters, numbers, spaces, apostrophes, and hyphens.");
        }
        return IdentityValidation.allowed(new ValidatedIdentity(name, NameNormalizer.normalize(name)));
    }

    public static OperationResult validatePlayerName(String value) {
        if (value == null || value.length() < 1 || value.length() > 16
            || !value.chars().allMatch(character -> asciiLetterOrDigit(character) || character == '_')) {
            return OperationResult.denied("Player names must contain 1 to 16 letters, numbers, or underscores.");
        }
        return OperationResult.ok("Player name is valid.");
    }

    public static OperationResult validateFoundingPayment(FoundingInventory inventory,
                                                            Map<io.github.empireage.civilizations.domain.ResourceKey, Long> materialCost,
                                                            BigDecimal availableMoney, BigDecimal moneyCost) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(materialCost, "materialCost");
        Objects.requireNonNull(availableMoney, "availableMoney");
        Objects.requireNonNull(moneyCost, "moneyCost");
        if (availableMoney.signum() < 0) return OperationResult.denied("Available money cannot be negative.");
        if (moneyCost.signum() < 0) return OperationResult.denied("Founding money cost cannot be negative.");
        if (availableMoney.compareTo(moneyCost) < 0) return OperationResult.denied("You cannot afford the founding money cost.");
        Map<?, Long> missing = inventory.shortfall(materialCost);
        if (!missing.isEmpty()) return OperationResult.denied("You no longer have all required founding materials.");
        return OperationResult.ok("Founding payment is available.");
    }

    public static int advisorLimit(int baseLimit, Collection<Map<String, Integer>> technologyModifiers) {
        if (baseLimit < 0) throw new IllegalArgumentException("baseLimit cannot be negative");
        Objects.requireNonNull(technologyModifiers, "technologyModifiers");
        long limit = baseLimit;
        for (Map<String, Integer> modifiers : technologyModifiers) {
            if (modifiers == null) continue;
            limit += Math.max(0, modifiers.getOrDefault("advisor-capacity", 0));
        }
        return (int) Math.min(Integer.MAX_VALUE, limit);
    }

    public static OperationResult validateAdvisorPromotion(int currentAdvisors, int advisorLimit) {
        if (currentAdvisors < 0 || advisorLimit < 0) throw new IllegalArgumentException("Advisor counts cannot be negative");
        return currentAdvisors < advisorLimit
            ? OperationResult.ok("An advisor slot is available.")
            : OperationResult.denied("The civilization has reached its advisor limit of " + advisorLimit + ".");
    }

    public static boolean established(Instant joinedAt, Instant lastActiveAt, long activeWindowSeconds, Instant now,
                                      Duration membershipAge, Duration activityWindow, Duration requiredActivity) {
        Objects.requireNonNull(joinedAt, "joinedAt");
        Objects.requireNonNull(lastActiveAt, "lastActiveAt");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(membershipAge, "membershipAge");
        Objects.requireNonNull(activityWindow, "activityWindow");
        Objects.requireNonNull(requiredActivity, "requiredActivity");
        if (activeWindowSeconds < 0) throw new IllegalArgumentException("activeWindowSeconds cannot be negative");
        return !joinedAt.plus(membershipAge).isAfter(now)
            && !lastActiveAt.isBefore(now.minus(activityWindow))
            && activeWindowSeconds >= requiredActivity.toSeconds();
    }

    public static OperationResult validateActivityObservation(Instant observedSince, Instant observedAt, Instant now) {
        if (observedSince == null || observedAt == null) {
            return OperationResult.denied("Activity interval timestamps are required.");
        }
        if (observedSince.isAfter(observedAt)) {
            return OperationResult.denied("Activity interval cannot end before it starts.");
        }
        if (observedAt.isAfter(Objects.requireNonNull(now, "now").plusSeconds(MAX_CLOCK_SKEW_SECONDS))) {
            return OperationResult.denied("Activity timestamp is too far in the future.");
        }
        return OperationResult.ok("Activity observation is valid.");
    }

    /**
     * Returns only the part of an observed online interval that has not already
     * been persisted. Using timestamps makes retries idempotent even when the
     * rolling activity-window total decreases as old days expire.
     */
    public static long unrecordedActivitySeconds(Instant observedSince, Instant observedAt,
                                                 Instant persistedLastActiveAt) {
        Objects.requireNonNull(observedSince, "observedSince");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(persistedLastActiveAt, "persistedLastActiveAt");
        if (observedSince.isAfter(observedAt)) {
            throw new IllegalArgumentException("Activity interval cannot end before it starts");
        }
        Instant effectiveStart = persistedLastActiveAt.isAfter(observedSince)
            ? persistedLastActiveAt : observedSince;
        return effectiveStart.isBefore(observedAt)
            ? Duration.between(effectiveStart, observedAt).toSeconds()
            : 0L;
    }

    public static boolean kickConfirmationRequired(int privatePlotCount, boolean plotTerminationConfirmed) {
        if (privatePlotCount < 0) throw new IllegalArgumentException("privatePlotCount cannot be negative");
        return privatePlotCount > 0 && !plotTerminationConfirmed;
    }

    public static Instant cooldownUntil(Instant departure, Duration cooldown) {
        Objects.requireNonNull(departure, "departure");
        Objects.requireNonNull(cooldown, "cooldown");
        if (cooldown.isNegative()) throw new IllegalArgumentException("cooldown cannot be negative");
        return departure.plus(cooldown);
    }

    private static boolean allowedNameCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        if (Character.isISOControl(codePoint) || type == Character.FORMAT || type == Character.SURROGATE
            || type == Character.PRIVATE_USE || type == Character.UNASSIGNED) return false;
        return Character.isLetterOrDigit(codePoint) || codePoint == ' ' || codePoint == '\'' || codePoint == '-';
    }

    private static boolean asciiLetterOrDigit(int character) {
        return character >= 'A' && character <= 'Z'
            || character >= 'a' && character <= 'z'
            || character >= '0' && character <= '9';
    }

    public record ValidatedIdentity(String name, String normalizedName) {
        public ValidatedIdentity {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(normalizedName, "normalizedName");
        }
    }

    public record IdentityValidation(boolean valid, String message, ValidatedIdentity identity) {
        public IdentityValidation {
            Objects.requireNonNull(message, "message");
            if (valid != (identity != null)) throw new IllegalArgumentException("Valid identity results must carry a value");
        }

        private static IdentityValidation allowed(ValidatedIdentity identity) {
            return new IdentityValidation(true, "Civilization name is valid.", identity);
        }

        private static IdentityValidation denied(String message) {
            return new IdentityValidation(false, message, null);
        }
    }
}
