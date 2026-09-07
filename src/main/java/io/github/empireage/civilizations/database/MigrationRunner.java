package io.github.empireage.civilizations.database;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.logging.Logger;

public final class MigrationRunner {
    private static final List<Migration> MIGRATIONS = List.of(
        new Migration(1, "initial schema", "db/migration/V1__initial_schema.sql"),
        new Migration(2, "persistent progression", "db/migration/V2__persistent_progression.sql"),
        new Migration(3, "civilization names only", "db/migration/V3__civilization_names_only.sql"),
        new Migration(4, "final progression catalog cleanup", "db/migration/V4__final_progression_catalog_cleanup.sql"),
        new Migration(5, "religion sacrifices", "db/migration/V5__religion_sacrifices.sql"),
        new Migration(6, "weekly plot taxes", "db/migration/V6__weekly_plot_taxes.sql"),
        new Migration(7, "deity favor", "db/migration/V7__deity_favor.sql"),
        new Migration(8, "three favor levels", "db/migration/V8__three_favor_levels.sql")
    );

    private MigrationRunner() {}

    public static void migrate(Connection connection, Logger logger) throws Exception {
        ensureHistory(connection);
        for (Migration migration : MIGRATIONS) {
            String script = read(migration.resource());
            String checksum = sha256(script);
            String installedChecksum = installedChecksum(connection, migration.version());
            if (installedChecksum != null) {
                if (!installedChecksum.equals(checksum)) throw new IllegalStateException(
                    "Applied migration V" + migration.version() + " checksum differs from the packaged migration");
                continue;
            }
            // MySQL commits DDL implicitly. V3 is one atomic ALTER, so a process
            // can stop after the schema change but before schema_history is
            // updated. Recognize that exact finished shape instead of retrying
            // DROP COLUMN and permanently blocking startup.
            if (migration.version() == 3 && civilizationNamesOnlySchema(connection)) {
                logger.warning("Recovering database migration V3 after its schema change committed without a history row.");
                recordInstalled(connection, migration, checksum);
                continue;
            }
            logger.info("Applying database migration V" + migration.version() + " — " + migration.description());
            executeScript(connection, script);
            recordInstalled(connection, migration, checksum);
        }
        ensureCurrentWarForeignKey(connection);
    }

    static boolean civilizationNamesOnlySchema(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'civilizations'
              AND COLUMN_NAME IN ('tag', 'normalized_tag')
            """); ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new IllegalStateException("Could not inspect the civilizations table");
            return result.getInt(1) == 0;
        }
    }

    private static void recordInstalled(Connection connection, Migration migration, String checksum) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO schema_history(version, description, checksum, installed_at, success) VALUES (?, ?, ?, ?, TRUE)")) {
            statement.setInt(1, migration.version());
            statement.setString(2, migration.description());
            statement.setString(3, checksum);
            statement.setTimestamp(4, java.sql.Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private static void ensureHistory(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS schema_history (
                    version INT UNSIGNED NOT NULL PRIMARY KEY,
                    description VARCHAR(190) NOT NULL,
                    checksum CHAR(64) NOT NULL,
                    installed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    success BOOLEAN NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        }
    }

    /**
     * The civilization-to-war reference is circular with the war ownership references, so it is
     * installed after both tables exist. Querying metadata first keeps startup idempotent even if
     * MySQL committed the ALTER before a process interruption.
     */
    private static void ensureCurrentWarForeignKey(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM information_schema.REFERENTIAL_CONSTRAINTS
            WHERE CONSTRAINT_SCHEMA = DATABASE()
              AND TABLE_NAME = 'civilizations'
              AND CONSTRAINT_NAME = 'fk_civilization_current_war'
            """)) {
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) return;
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                ALTER TABLE civilizations
                ADD CONSTRAINT fk_civilization_current_war
                FOREIGN KEY (current_war_id) REFERENCES wars(id)
                """);
        }
    }

    private static String installedChecksum(Connection connection, int version) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT checksum FROM schema_history WHERE version = ? AND success = TRUE")) {
            statement.setInt(1, version);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    static void executeScript(Connection connection, String script) throws Exception {
        String withoutComments = script.replaceAll("(?m)^\\s*--.*$", "");
        for (String raw : withoutComments.split(";(?=\\s*(?:CREATE|ALTER|INSERT|UPDATE|DELETE|$))")) {
            String statementText = raw.strip();
            if (statementText.isEmpty()) continue;
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementText);
            }
        }
    }

    private static String read(String name) throws IOException {
        try (InputStream stream = MigrationRunner.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new IOException("Missing migration resource " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sha256(String input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    private record Migration(int version, String description, String resource) {}
}
