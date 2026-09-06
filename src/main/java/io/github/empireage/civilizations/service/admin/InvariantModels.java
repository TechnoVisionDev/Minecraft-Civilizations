package io.github.empireage.civilizations.service.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Structured output from the persisted-state invariant scan. */
public final class InvariantModels {
    private InvariantModels() {}

    public enum Kind {
        DUPLICATE_MEMBERSHIP,
        ORPHAN_MEMBERSHIP,
        DISCONNECTED_CLAIMS,
        INVALID_PLOT_OWNER,
        MULTIPLE_CURRENT_WARS,
        WAR_POINTER_MISMATCH,
        UNDEFINED_RESEARCH,
        ECONOMY_COMPENSATION
    }

    public enum Severity {
        WARNING,
        ERROR
    }

    public record Violation(Kind kind, Severity severity, String identity, String message,
                            Map<String, String> context) {
        public Violation {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(message, "message");
            context = Map.copyOf(context);
        }
    }

    public record Report(Instant startedAt, Instant completedAt, List<Violation> violations) {
        public Report {
            violations = List.copyOf(violations);
        }

        public boolean healthy() {
            return violations.isEmpty();
        }

        public long errorCount() {
            return violations.stream().filter(value -> value.severity() == Severity.ERROR).count();
        }

        public Duration duration() {
            return Duration.between(startedAt, completedAt);
        }

        public Map<Kind, Long> counts() {
            EnumMap<Kind, Long> counts = new EnumMap<>(Kind.class);
            for (Violation violation : violations) counts.merge(violation.kind(), 1L, Long::sum);
            return Map.copyOf(counts);
        }
    }
}
