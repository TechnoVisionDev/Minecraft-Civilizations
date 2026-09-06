package io.github.empireage.civilizations.config;

import io.github.empireage.civilizations.util.DurationParser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Data-driven pantheon, offerings, and blessings. */
public record ReligionCatalog(Map<String, God> gods, Duration cooldown, Duration buffDuration) {
    public record God(String key, String name, Material icon, Material offering, List<Buff> buffs,
                      List<String> description) {}
    public record Buff(String effect, int amplifier) {}

    public ReligionCatalog {
        gods = Collections.unmodifiableMap(new LinkedHashMap<>(gods));
        if (gods.isEmpty()) throw new IllegalArgumentException("Religion catalog requires at least one god");
        if (cooldown == null || cooldown.isNegative() || cooldown.isZero()) {
            throw new IllegalArgumentException("Sacrifice cooldown must be positive");
        }
        if (buffDuration == null || buffDuration.isNegative() || buffDuration.isZero()) {
            throw new IllegalArgumentException("Blessing duration must be positive");
        }
    }

    public God get(String key) {
        return key == null ? null : gods.get(key.toLowerCase(Locale.ROOT));
    }

    public static ReligionCatalog load(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("gods");
        if (section == null) throw new IllegalArgumentException("religion.yml has no gods section");
        Map<String, God> gods = new LinkedHashMap<>();
        for (String rawKey : section.getKeys(false)) {
            String key = rawKey.toLowerCase(Locale.ROOT);
            if (!key.matches("[a-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid god key " + rawKey);
            ConfigurationSection data = section.getConfigurationSection(rawKey);
            if (data == null) throw new IllegalArgumentException("Invalid god entry " + rawKey);
            Material icon = requireItem(data.getString("icon"), key + " icon");
            Material offering = requireItem(data.getString("offering"), key + " offering");
            List<Buff> buffs = data.getMapList("buffs").stream().map(value -> {
                Object rawEffect = value.get("effect");
                String effect = String.valueOf(rawEffect == null ? "" : rawEffect).toLowerCase(Locale.ROOT);
                int amplifier = value.get("amplifier") instanceof Number number ? number.intValue() : 0;
                if (!effect.matches("[a-z0-9_]+") || amplifier < 0 || amplifier > 10) {
                    throw new IllegalArgumentException("Invalid blessing for " + key);
                }
                if (Bukkit.getServer() != null && Registry.EFFECT.get(NamespacedKey.minecraft(effect)) == null) {
                    throw new IllegalArgumentException("Unknown potion effect " + effect + " for " + key);
                }
                return new Buff(effect, amplifier);
            }).toList();
            if (buffs.isEmpty()) throw new IllegalArgumentException(key + " must grant at least one blessing");
            gods.put(key, new God(key, data.getString("name", rawKey), icon, offering, buffs,
                List.copyOf(data.getStringList("description"))));
        }
        return new ReligionCatalog(gods,
            DurationParser.parse(yaml.getString("cooldown", "4h")),
            DurationParser.parse(yaml.getString("buff-duration", "30m")));
    }

    private static Material requireItem(String value, String label) {
        Material material = Material.matchMaterial(value == null ? "" : value);
        if (material == null || material == Material.AIR || material == Material.CAVE_AIR
            || material == Material.VOID_AIR || Bukkit.getServer() != null && !material.isItem()) {
            throw new IllegalArgumentException("Unknown or unusable " + label + ": " + value);
        }
        return material;
    }
}
