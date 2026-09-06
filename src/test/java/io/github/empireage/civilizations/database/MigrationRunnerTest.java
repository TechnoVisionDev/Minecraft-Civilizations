package io.github.empireage.civilizations.database;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MigrationRunnerTest {
    @Test
    void recognizesAnInterruptedV3AfterItsAtomicAlterCommitted() throws Exception {
        assertTrue(namesOnlySchemaWithLegacyColumnCount(0));
        assertFalse(namesOnlySchemaWithLegacyColumnCount(2));
    }

    private boolean namesOnlySchemaWithLegacyColumnCount(int columns) throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(columns);
        return MigrationRunner.civilizationNamesOnlySchema(connection);
    }
}
