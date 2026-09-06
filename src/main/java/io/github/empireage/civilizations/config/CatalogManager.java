package io.github.empireage.civilizations.config;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicReference;

public final class CatalogManager {
    private static final int RESOURCE_CATALOG_VERSION = 2;
    private static final int TECHNOLOGY_CATALOG_VERSION = 3;
    private static final int WORK_ORDER_CATALOG_VERSION = 3;
    private static final int RELIGION_CATALOG_VERSION = 1;
    private final JavaPlugin plugin;
    private final AtomicReference<ResourceCatalog> resources = new AtomicReference<>();
    private final AtomicReference<TechnologyCatalog> technologies = new AtomicReference<>();
    private final AtomicReference<WorkOrderCatalog> workOrders = new AtomicReference<>();
    private final AtomicReference<ReligionCatalog> religion = new AtomicReference<>();

    public CatalogManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        ensure("resources.yml");
        ensure("technologies.yml");
        ensure("workorders.yml");
        ensure("religion.yml");
        ensureCurrentCatalog("resources.yml");
        ensureCurrentCatalog("technologies.yml");
        ensureCurrentCatalog("workorders.yml");
        ensureCurrentCatalog("religion.yml");
        ResourceCatalog newResources = ResourceCatalog.load(new File(plugin.getDataFolder(), "resources.yml"));
        TechnologyCatalog newTechnologies = TechnologyCatalog.load(new File(plugin.getDataFolder(), "technologies.yml"), newResources);
        WorkOrderCatalog newWorkOrders = loadWorkOrders(newResources);
        ReligionCatalog newReligion = ReligionCatalog.load(new File(plugin.getDataFolder(), "religion.yml"));
        resources.set(newResources);
        technologies.set(newTechnologies);
        workOrders.set(newWorkOrders);
        religion.set(newReligion);
    }

    public ResourceCatalog resources() { return resources.get(); }
    public TechnologyCatalog technologies() { return technologies.get(); }
    public WorkOrderCatalog workOrders() { return workOrders.get(); }
    public ReligionCatalog religion() { return religion.get(); }

    private void ensure(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) plugin.saveResource(name, false);
    }

    private WorkOrderCatalog loadWorkOrders(ResourceCatalog newResources) {
        File file = new File(plugin.getDataFolder(), "workorders.yml");
        try {
            return WorkOrderCatalog.load(file, newResources);
        } catch (WorkOrderCatalog.LegacyCatalogException legacy) {
            File backup = nextBackup(file);
            try {
                Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            } catch (IOException failure) {
                throw new IllegalStateException("The legacy workorders.yml needs an automatic upgrade, but it could not be "
                    + "backed up to " + backup.getName() + ": " + failure.getMessage(), failure);
            }
            plugin.getLogger().warning("Legacy workorders.yml detected (" + legacy.getMessage() + "). Backed it up as "
                + backup.getName() + " and restored the current packaged catalog.");
            plugin.saveResource("workorders.yml", true);
            return WorkOrderCatalog.load(file, newResources);
        }
    }

    private void ensureCurrentCatalog(String name) {
        File file = new File(plugin.getDataFolder(), name);
        int current = switch (name) {
            case "resources.yml" -> RESOURCE_CATALOG_VERSION;
            case "technologies.yml" -> TECHNOLOGY_CATALOG_VERSION;
            case "workorders.yml" -> WORK_ORDER_CATALOG_VERSION;
            case "religion.yml" -> RELIGION_CATALOG_VERSION;
            default -> throw new IllegalArgumentException("Unknown catalog: " + name);
        };
        int installed = YamlConfiguration.loadConfiguration(file).getInt("catalog-version", 0);
        if (installed == current) return;
        File backup = nextBackup(file, ".pre-v" + current + ".bak");
        try {
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException failure) {
            throw new IllegalStateException(name + " must be upgraded from catalog version " + installed + " to "
                + current + ", but it could not be backed up to " + backup.getName() + ": "
                + failure.getMessage(), failure);
        }
        plugin.getLogger().warning("Outdated " + name + " catalog version " + installed + " detected. Backed it up as "
            + backup.getName() + " and restored catalog version " + current + ".");
        plugin.saveResource(name, true);
    }

    private static File nextBackup(File source) {
        return nextBackup(source, ".pre-category.bak");
    }

    private static File nextBackup(File source, String suffix) {
        File parent = source.getParentFile();
        String base = source.getName() + suffix;
        File candidate = new File(parent, base);
        for (int index = 1; candidate.exists(); index++) candidate = new File(parent, base + "." + index);
        return candidate;
    }
}
