package io.github.empireage.civilizations.runtime;

import java.util.Locale;
import java.util.Optional;

/** The audience selected for a player's ordinary chat messages. */
public enum ChatChannel {
    GLOBAL,
    LOCAL,
    CIV;

    public static Optional<ChatChannel> parse(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(valueOf(value.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public String displayName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
