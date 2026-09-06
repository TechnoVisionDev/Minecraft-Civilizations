package io.github.empireage.civilizations.database;

import io.github.empireage.civilizations.util.UuidBytes;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

public final class Sql {
    private Sql() {}

    public static PreparedStatement prepare(Connection connection, String sql, Object... values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        bind(statement, values);
        return statement;
    }

    public static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            int index = i + 1;
            switch (value) {
                case null -> statement.setObject(index, null);
                case UUID uuid -> statement.setBytes(index, UuidBytes.toBytes(uuid));
                case Instant instant -> statement.setTimestamp(index, Timestamp.from(instant));
                case Enum<?> enumeration -> statement.setString(index, enumeration.name());
                case BigDecimal decimal -> statement.setBigDecimal(index, decimal);
                default -> statement.setObject(index, value);
            }
        }
    }

    public static long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet result = statement.getGeneratedKeys()) {
            if (!result.next()) throw new SQLException("Insert returned no generated key");
            return result.getLong(1);
        }
    }
}
