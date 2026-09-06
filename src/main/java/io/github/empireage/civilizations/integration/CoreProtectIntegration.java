package io.github.empireage.civilizations.integration;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/**
 * CoreProtect is intentionally status-only in v1. Block rollback remains an
 * administrator workflow performed through CoreProtect itself.
 */
public final class CoreProtectIntegration {
    private final JavaPlugin plugin;

    public CoreProtectIntegration(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public IntegrationStatus status() {
        Plugin coreProtect = plugin.getServer().getPluginManager().getPlugin("CoreProtect");
        if (coreProtect == null || !coreProtect.isEnabled()) return IntegrationStatus.missing("CoreProtect");
        return new IntegrationStatus("CoreProtect", true, false, coreProtect.getDescription().getVersion(),
            "Available for administrator investigation and manual rollback; automatic rollback is not enabled.");
    }
}
