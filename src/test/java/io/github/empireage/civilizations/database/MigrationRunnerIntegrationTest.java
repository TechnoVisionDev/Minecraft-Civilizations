package io.github.empireage.civilizations.database;

import io.github.empireage.civilizations.cache.StateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.DriverManager;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Opt-in smoke test for the packaged migration against a real MySQL server.
 *
 * <p>Example:
 * {@code mvn -Dcivilizations.mysql.it=true
 * -Dcivilizations.mysql.url=jdbc:mysql://127.0.0.1:3306/civilizations_test test}</p>
 */
@EnabledIfSystemProperty(named = "civilizations.mysql.it", matches = "true")
final class MigrationRunnerIntegrationTest {
    @Test
    void packagedMigrationAppliesTwiceAndInstallsCircularForeignKey() throws Exception {
        String url = System.getProperty("civilizations.mysql.url");
        String user = System.getProperty("civilizations.mysql.user", "root");
        String password = System.getProperty("civilizations.mysql.password", "");

        try (var connection = DriverManager.getConnection(url, user, password)) {
            MigrationRunner.migrate(connection, Logger.getLogger("migration-test"));
            MigrationRunner.migrate(connection, Logger.getLogger("migration-test"));

            var snapshot = new StateRepository().load(connection);
            assertEquals(0, snapshot.civilizations().size());
            assertEquals(0, snapshot.claims().size());

            try (var statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'civilizations'
                  AND CONSTRAINT_NAME = 'fk_civilization_current_war'
                """)) {
                try (var result = statement.executeQuery()) {
                    result.next();
                    assertEquals(1, result.getInt(1));
                }
            }
        }
    }
}
