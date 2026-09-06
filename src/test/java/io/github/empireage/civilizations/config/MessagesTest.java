package io.github.empireage.civilizations.config;

import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessagesTest {
    @TempDir
    Path directory;

    @Test
    void missingInstalledMessageUsesCurrentPackagedDefault() throws Exception {
        Files.writeString(directory.resolve("messages.yml"), "prefix: '&7Old: '\n");
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getResource("messages.yml")).thenReturn(new ByteArrayInputStream(("""
            prefix: "&8[&6Civilizations&8] &r"
            sell-usage: "&eUsage: /sell <hand|all|list>"
            """).getBytes(StandardCharsets.UTF_8)));

        Messages messages = new Messages(plugin);
        messages.load();

        assertEquals(ChatColor.YELLOW + "Usage: /sell <hand|all|list>", messages.text("sell-usage"));
        assertEquals(ChatColor.GRAY + "Old: ", messages.text("prefix"));
    }
}
