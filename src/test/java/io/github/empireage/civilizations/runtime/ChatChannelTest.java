package io.github.empireage.civilizations.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChatChannelTest {
    @Test
    void parsesEveryCommandChannelCaseInsensitively() {
        assertEquals(ChatChannel.GLOBAL, ChatChannel.parse("global").orElseThrow());
        assertEquals(ChatChannel.LOCAL, ChatChannel.parse("LOCAL").orElseThrow());
        assertEquals(ChatChannel.CIV, ChatChannel.parse(" civ ").orElseThrow());
    }

    @Test
    void rejectsUnknownAndBlankChannels() {
        assertTrue(ChatChannel.parse("civilization").isEmpty());
        assertTrue(ChatChannel.parse(" ").isEmpty());
        assertTrue(ChatChannel.parse(null).isEmpty());
    }
}
