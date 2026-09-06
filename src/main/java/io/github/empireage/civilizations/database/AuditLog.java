package io.github.empireage.civilizations.database;

import io.github.empireage.civilizations.util.UuidBytes;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class AuditLog {
    private static volatile String buildVersion = "unknown";

    private AuditLog() {}

    public static void configureBuildVersion(String version) {
        buildVersion = Objects.requireNonNullElse(version, "unknown");
    }

    public static void write(Connection connection, Long civilizationId, UUID actor, String action,
                             String targetType, String targetId, Map<String, ?> metadata, String serverId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO civ_audit_log(civ_id, actor_uuid, action_key, target_type, target_id, metadata, server_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            if (civilizationId == null) statement.setObject(1, null); else statement.setLong(1, civilizationId);
            statement.setBytes(2, UuidBytes.toBytes(actor));
            statement.setString(3, action);
            statement.setString(4, targetType);
            statement.setString(5, targetId);
            Map<String, Object> context = new LinkedHashMap<>();
            if (metadata != null) metadata.forEach(context::put);
            context.putIfAbsent("pluginVersion", buildVersion);
            statement.setString(6, JsonData.object(context));
            statement.setString(7, serverId);
            statement.setTimestamp(8, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }
}
