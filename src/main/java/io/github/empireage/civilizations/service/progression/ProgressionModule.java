package io.github.empireage.civilizations.service.progression;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.config.CatalogManager;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.listener.progression.CivicResourceListener;
import io.github.empireage.civilizations.listener.progression.DepositRecoveryListener;
import io.github.empireage.civilizations.listener.progression.ProgressionGui;
import io.github.empireage.civilizations.listener.progression.PortalSuppressionListener;
import io.github.empireage.civilizations.listener.progression.RefiningListener;
import io.github.empireage.civilizations.listener.progression.TechnologyGateListener;
import io.github.empireage.civilizations.listener.progression.VillagerTradingListener;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Composition and listener-registration surface for the complete progression subsystem. */
public final class ProgressionModule implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Settings settings;
    private final CivicItemService civicItems;
    private final RefiningService refining;
    private final StockpileService stockpile;
    private final WorkOrderService workOrders;
    private final DepositService deposits;
    private final ResearchService research;
    private final TechnologyAccess technology;
    private final ProgressionGui menus;
    private final List<Listener> listeners;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    public ProgressionModule(JavaPlugin plugin, Settings settings, CatalogManager catalogs,
                             Database database, StateCache cache) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(catalogs, "catalogs");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(cache, "cache");
        civicItems = new CivicItemService(plugin, catalogs.resources());
        refining = new RefiningService(civicItems);
        stockpile = new StockpileService(database, cache, settings.serverId(), catalogs.resources());
        workOrders = new WorkOrderService(database, cache, catalogs.workOrders(), stockpile, settings.serverId());
        deposits = new DepositService(plugin, database, cache, civicItems, stockpile, settings.serverId());
        research = new ResearchService(database, cache, catalogs.technologies(), stockpile, settings);
        technology = new TechnologyAccess(cache, catalogs.technologies(), settings.technology());
        menus = new ProgressionGui(plugin, cache, catalogs.technologies(), catalogs.resources(), civicItems, research, workOrders);
        listeners = List.of(
            new CivicResourceListener(civicItems),
            new RefiningListener(refining, civicItems),
            new DepositRecoveryListener(deposits),
            new TechnologyGateListener(technology),
            new PortalSuppressionListener(),
            new VillagerTradingListener(),
            menus
        );
    }

    /** Registers recipes/listeners and starts restart-safe due-time polling. Safe to call once. */
    public void enable() {
        if (!enabled.compareAndSet(false, true)) return;
        civicItems.registerRecipes();
        listeners.forEach(listener -> plugin.getServer().getPluginManager().registerEvents(listener, plugin));
        plugin.getServer().getOnlinePlayers().forEach(player -> {
            civicItems.normalizeNames(player.getInventory());
            civicItems.normalizeNames(player.getEnderChest());
            civicItems.normalizeNames(player.getOpenInventory().getTopInventory());
            var cursor = player.getItemOnCursor();
            if (civicItems.normalizeName(cursor)) player.setItemOnCursor(cursor);
        });
        long researchPeriod = Math.max(20L, settings.notifications().researchCheck().toSeconds() * 20L);
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::pollResearch, 1L, researchPeriod));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin,
            () -> workOrders.ensureAllOrders(Instant.now()).exceptionally(error -> null), 20L, 1200L));
    }

    private void pollResearch() {
        research.completeDue(Instant.now()).thenAccept(completions -> {
            if (completions.isEmpty() || !plugin.isEnabled() || !enabled.get()) return;
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!plugin.isEnabled() || !enabled.get()) return;
                    completions.stream().filter(ResearchService.Completion::newlyUnlocked)
                        .forEach(completion -> plugin.getServer().getOnlinePlayers().stream()
                            .filter(player -> technology.civilizationId(player.getUniqueId()).orElse(-1) == completion.civilizationId())
                            .forEach(player -> player.sendMessage("Research complete: " + completion.technologyKey() + ".")));
                });
            } catch (RuntimeException ignored) {
                // An async completion may race plugin shutdown.
            }
        }).exceptionally(error -> null);
    }

    public List<Listener> listeners() { return listeners; }
    public CivicItemService civicItems() { return civicItems; }
    public RefiningService refining() { return refining; }
    public StockpileService stockpile() { return stockpile; }
    public WorkOrderService workOrders() { return workOrders; }
    public DepositService deposits() { return deposits; }
    public ResearchService research() { return research; }
    public TechnologyAccess technology() { return technology; }
    public ProgressionGui menus() { return menus; }

    @Override
    public void close() {
        boolean wasEnabled = enabled.getAndSet(false);
        deposits.close();
        if (!wasEnabled) return;
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        listeners.forEach(HandlerList::unregisterAll);
        civicItems.unregisterRecipes();
    }
}
