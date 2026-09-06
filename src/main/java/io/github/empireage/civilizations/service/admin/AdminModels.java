package io.github.empireage.civilizations.service.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Value objects returned by the asynchronous administration API. */
public final class AdminModels {
    private AdminModels() {}

    public record Result(boolean success, boolean changed, String message, List<String> warnings) {
        public Result {
            Objects.requireNonNull(message, "message");
            warnings = List.copyOf(warnings);
        }

        public static Result changed(String message) {
            return new Result(true, true, message, List.of());
        }

        public static Result changed(String message, List<String> warnings) {
            return new Result(true, true, message, warnings);
        }

        public static Result unchanged(String message) {
            return new Result(true, false, message, List.of());
        }

        public static Result denied(String message) {
            return new Result(false, false, message, List.of());
        }

        public static Result confirmationRequired(String message, List<String> warnings) {
            return new Result(false, false, message, warnings);
        }
    }

    /**
     * A diagnostic deliberately uses string values so a command renderer can
     * display it without exposing live JDBC objects or mutable domain state.
     */
    public record Inspection(
        String subjectType,
        String subjectId,
        Instant inspectedAt,
        Map<String, String> cached,
        Map<String, String> persisted,
        List<String> warnings
    ) {
        public Inspection {
            Objects.requireNonNull(subjectType, "subjectType");
            Objects.requireNonNull(subjectId, "subjectId");
            Objects.requireNonNull(inspectedAt, "inspectedAt");
            cached = Map.copyOf(cached);
            persisted = Map.copyOf(persisted);
            warnings = List.copyOf(warnings);
        }
    }

    public record ForcePreview(
        String operation,
        String target,
        boolean possible,
        boolean confirmationRequired,
        List<String> warnings
    ) {
        public ForcePreview {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(target, "target");
            warnings = List.copyOf(warnings);
        }
    }

    public record AuditFilter(
        Long civilizationId,
        UUID actorId,
        String actionPrefix,
        String targetType,
        String targetId,
        Instant since,
        Instant before,
        int page,
        int pageSize
    ) {
        public AuditFilter {
            actionPrefix = clean(actionPrefix);
            targetType = clean(targetType);
            targetId = clean(targetId);
            if (page < 1) page = 1;
            if (pageSize < 1) pageSize = 20;
            if (pageSize > 100) pageSize = 100;
            if (since != null && before != null && !since.isBefore(before)) {
                throw new IllegalArgumentException("Audit 'since' must be before 'before'");
            }
        }

        public static AuditFilter recent() {
            return new AuditFilter(null, null, null, null, null, null, null, 1, 20);
        }

        private static String clean(String value) {
            if (value == null || value.isBlank()) return null;
            return value.strip();
        }
    }

    public record AuditEntry(
        long id,
        Long civilizationId,
        UUID actorId,
        String action,
        String targetType,
        String targetId,
        String metadataJson,
        String serverId,
        Instant createdAt
    ) {}

    public record Migration(
        int version,
        String description,
        String installedChecksum,
        String packagedChecksum,
        boolean success,
        Instant installedAt,
        String status
    ) {}

    public record SchemaReport(
        boolean databaseHealthy,
        String databaseProduct,
        String schema,
        int currentVersion,
        int expectedVersion,
        boolean upToDate,
        List<Migration> migrations,
        List<String> missingTables,
        List<String> warnings,
        Instant inspectedAt
    ) {
        public SchemaReport {
            migrations = List.copyOf(migrations);
            missingTables = List.copyOf(missingTables);
            warnings = List.copyOf(warnings);
        }
    }
}
