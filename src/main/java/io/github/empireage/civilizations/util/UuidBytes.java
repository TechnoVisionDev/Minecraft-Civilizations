package io.github.empireage.civilizations.util;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class UuidBytes {
    private UuidBytes() {}

    public static byte[] toBytes(UUID uuid) {
        if (uuid == null) return null;
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }

    public static UUID fromBytes(byte[] bytes) {
        if (bytes == null) return null;
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public static UUID get(ResultSet result, String column) throws SQLException {
        return fromBytes(result.getBytes(column));
    }
}
