package io.github.empireage.civilizations.config;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class Messages {
    private final JavaPlugin plugin;
    private final AtomicReference<YamlConfiguration> values = new AtomicReference<>();
    private final AtomicReference<YamlConfiguration> packaged = new AtomicReference<>(new YamlConfiguration());

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (InputStream defaults = plugin.getResource("messages.yml")) {
            if (defaults != null) {
                packaged.set(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8)));
            }
        } catch (java.io.IOException exception) {
            plugin.getLogger().warning("Could not close the packaged messages.yml resource: " + exception.getMessage());
        }
        values.set(loaded);
    }

    public String text(String key) {
        return color(raw(key));
    }

    public String text(String key, Map<String, ?> replacements) {
        String result = raw(key);
        for (Map.Entry<String, ?> entry : replacements.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return color(result);
    }

    private String raw(String key) {
        String configured = values.get().getString(key);
        if (configured != null) return configured;
        return packaged.get().getString(key, key);
    }

    public void send(CommandSender sender, String key) {
        sender.sendMessage(text("prefix") + text(key));
    }

    public void send(CommandSender sender, String key, Map<String, ?> replacements) {
        sender.sendMessage(text("prefix") + text(key, replacements));
    }

    public void sendRaw(CommandSender sender, String message) {
        sender.sendMessage(text("prefix") + color(message));
    }

    @SuppressWarnings("deprecation")
    public static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }
}
