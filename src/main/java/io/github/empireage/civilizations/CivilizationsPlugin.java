package io.github.empireage.civilizations;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.cache.StateRepository;
import io.github.empireage.civilizations.command.CivCommand;
import io.github.empireage.civilizations.command.ChatCommand;
import io.github.empireage.civilizations.command.CommandPorts;
import io.github.empireage.civilizations.command.ConfirmationGui;
import io.github.empireage.civilizations.command.SellCommand;
import io.github.empireage.civilizations.command.ReligionCommand;
import io.github.empireage.civilizations.concurrent.CivilizationLocks;
import io.github.empireage.civilizations.config.CatalogManager;
import io.github.empireage.civilizations.config.Messages;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.database.AuditLog;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.integration.CoreProtectIntegration;
import io.github.empireage.civilizations.integration.BukkitClaimEnvironment;
import io.github.empireage.civilizations.integration.IntegrationStatus;
import io.github.empireage.civilizations.integration.PlaceholderApiIntegration;
import io.github.empireage.civilizations.integration.PlaceholderValues;
import io.github.empireage.civilizations.integration.TravelArrivalClaimEnvironment;
import io.github.empireage.civilizations.integration.WorldGuardClaimEnvironment;
import io.github.empireage.civilizations.listener.protection.ClaimProtectionListener;
import io.github.empireage.civilizations.listener.war.WarListener;
import io.github.empireage.civilizations.runtime.ActivityTracker;
import io.github.empireage.civilizations.runtime.CivilizationChat;
import io.github.empireage.civilizations.runtime.ClaimEntryListener;
import io.github.empireage.civilizations.runtime.DimensionTeleportService;
import io.github.empireage.civilizations.runtime.WildTeleportService;
import io.github.empireage.civilizations.runtime.TutorialBookService;
import io.github.empireage.civilizations.runtime.EstablishmentRecalculator;
import io.github.empireage.civilizations.runtime.FoundingCoordinator;
import io.github.empireage.civilizations.runtime.HomeTeleportService;
import io.github.empireage.civilizations.runtime.PlotTaxRuntime;
import io.github.empireage.civilizations.service.economy.PlotTaxService;
import io.github.empireage.civilizations.service.admin.AdminService;
import io.github.empireage.civilizations.service.admin.AdminCommandAdapter;
import io.github.empireage.civilizations.service.admin.AdminModels;
import io.github.empireage.civilizations.service.admin.InvariantChecker;
import io.github.empireage.civilizations.service.economy.EconomyRecoveryService;
import io.github.empireage.civilizations.service.economy.EconomyService;
import io.github.empireage.civilizations.service.economy.TreasuryService;
import io.github.empireage.civilizations.service.lifecycle.CivilizationLifecycleService;
import io.github.empireage.civilizations.service.progression.ProgressionModule;
import io.github.empireage.civilizations.service.religion.ReligionService;
import io.github.empireage.civilizations.service.territory.ClaimEnvironmentPort;
import io.github.empireage.civilizations.service.territory.ConfirmationTokens;
import io.github.empireage.civilizations.service.territory.PlotService;
import io.github.empireage.civilizations.service.territory.ProtectionService;
import io.github.empireage.civilizations.service.territory.SnapshotWarAccess;
import io.github.empireage.civilizations.service.territory.TerritoryService;
import io.github.empireage.civilizations.service.war.WarItems;
import io.github.empireage.civilizations.service.war.WarRuntime;
import io.github.empireage.civilizations.service.war.WarService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Main composition root. MySQL may start late; protection remains fail-closed until the first cache load. */
public final class CivilizationsPlugin extends JavaPlugin {
    private final AtomicBoolean cacheRefreshRunning = new AtomicBoolean();
    private final AtomicBoolean readyLogged = new AtomicBoolean();

    private Settings settings;
    private Messages messages;
    private CatalogManager catalogs;
    private Database database;
    private StateCache cache;
    private ProgressionModule progression;
    private WarItems warItems;
    private WarRuntime warRuntime;
    private ActivityTracker activityTracker;
    private FoundingCoordinator founding;
    private HomeTeleportService homeTeleport;
    private DimensionTeleportService dimensionTeleport;
    private WildTeleportService wildTeleport;
    private EconomyRecoveryService economyRecovery;
    private PlotTaxService plotTaxes;
    private PlotTaxRuntime plotTaxRuntime;
    private EstablishmentRecalculator establishmentRecalculator;
    private InvariantChecker invariantChecker;
    private PlaceholderApiIntegration placeholderApi;
    private BukkitTask reconnectTask;
    private BukkitTask cacheRefreshTask;

    @Override
    public void onEnable() {
        try {
            initialize();
        } catch (Throwable failure) {
            getLogger().log(Level.SEVERE, "Civilizations could not initialize safely; disabling the plugin.", failure);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void initialize() {
        saveDefaultConfig();
        settings = Settings.load(getConfig());
        messages = new Messages(this);
        messages.load();
        catalogs = new CatalogManager(this);
        catalogs.load();
        AuditLog.configureBuildVersion(getDescription().getVersion());

        database = new Database(settings.database(), getLogger());
        cache = new StateCache(database, new StateRepository());
        CivilizationLocks locks = new CivilizationLocks(256);
        Clock clock = Clock.systemUTC();
        ConfirmationTokens confirmations = new ConfirmationTokens(clock, settings.plots().confirmationExpiry());

        ClaimEnvironmentPort environment = claimEnvironment();
        CivilizationLifecycleService lifecycle = new CivilizationLifecycleService(
            database, cache, locks, settings, catalogs.technologies());
        EconomyService economy = new EconomyService(this, database, settings);
        progression = new ProgressionModule(this, settings, catalogs, database, cache);
        TerritoryService territory = new TerritoryService(database, cache, locks, settings,
            catalogs::resources, catalogs::technologies, environment, confirmations, clock);
        PlotService plots = new PlotService(database, cache, locks, settings,
            catalogs::technologies, economy.port(), confirmations, clock);
        plotTaxes = new PlotTaxService(database, cache, economy, settings.serverId());
        plotTaxRuntime = new PlotTaxRuntime(this, cache, plotTaxes);
        TreasuryService treasury = new TreasuryService(database, cache, locks, settings, economy);
        WarService wars = new WarService(database, cache, settings, catalogs, progression.stockpile(), locks);
        ProtectionService protection = new ProtectionService(cache, settings.protection(),
            new SnapshotWarAccess(cache, settings.war()), clock, settings.war().attackerBlockDrops());
        AdminService admin = new AdminService(database, cache, locks, settings, catalogs.resources(), catalogs.technologies());

        founding = new FoundingCoordinator(this, settings, progression.civicItems(), lifecycle, economy,
            progression.workOrders());
        CivilizationChat civilizationChat = new CivilizationChat(this, cache, lifecycle,
            settings.chat().localRadiusBlocks());
        homeTeleport = new HomeTeleportService(this, cache, settings.plots());
        dimensionTeleport = new DimensionTeleportService(this, progression.technology(), cache, settings.travel());
        activityTracker = new ActivityTracker(this, lifecycle);
        establishmentRecalculator = new EstablishmentRecalculator(this, database, cache, settings);
        economyRecovery = new EconomyRecoveryService(this, database, economy, plots, treasury);
        invariantChecker = new InvariantChecker(database, catalogs.technologies(), getLogger());

        warItems = new WarItems(this);
        warRuntime = new WarRuntime(this, cache, wars, warItems, settings.war());

        registerGameplayListeners(protection, wars, civilizationChat);
        progression.enable();
        warItems.registerRecipes();
        activityTracker.start();
        establishmentRecalculator.start();
        economyRecovery.start();
        getServer().getPluginManager().registerEvents(plotTaxRuntime, this);
        plotTaxRuntime.start();
        warRuntime.start(toTicks(settings.notifications().warTick()));

        registerCommand(lifecycle, territory, plots, protection, treasury, wars, founding,
            civilizationChat, environment, admin);
        registerChatCommand(civilizationChat);
        registerSellCommand(economy);
        registerReligionCommands();
        registerWildCommand();
        registerDimensionCommands();
        registerOptionalIntegrations();
        scheduleStorageMaintenance();
        connectAndWarmCache();

        getLogger().info("Civilizations " + getDescription().getVersion()
            + " enabled for Spigot API 26.2; territory protection is fail-closed until MySQL is ready.");
    }

    private ClaimEnvironmentPort claimEnvironment() {
        ClaimEnvironmentPort regions = getConfig().getBoolean("integrations.worldguard", true)
            ? new WorldGuardClaimEnvironment(this, true) : ClaimEnvironmentPort.ALLOW_ALL;
        return new BukkitClaimEnvironment(new TravelArrivalClaimEnvironment(settings.travel(), regions));
    }

    private void registerGameplayListeners(ProtectionService protection, WarService wars,
                                           CivilizationChat civilizationChat) {
        PluginManager plugins = getServer().getPluginManager();
        // Protection is registered before storage startup so an uninitialized cache never looks like wilderness.
        plugins.registerEvents(new ClaimProtectionListener(protection), this);
        plugins.registerEvents(new WarListener(this, cache, wars, warItems, settings.war()), this);
        plugins.registerEvents(civilizationChat, this);
        plugins.registerEvents(homeTeleport, this);
        plugins.registerEvents(dimensionTeleport, this);
        plugins.registerEvents(activityTracker, this);
        plugins.registerEvents(founding, this);
        plugins.registerEvents(new ClaimEntryListener(cache), this);
    }

    private void registerCommand(CivilizationLifecycleService lifecycle, TerritoryService territory,
                                 PlotService plots, ProtectionService protection, TreasuryService treasury,
                                 WarService wars, FoundingCoordinator founding, CivilizationChat civilizationChat,
                                 ClaimEnvironmentPort environment, AdminService admin) {
        CommandPorts.FoundingPort foundingPort = (player, name) -> {
            Location location = player.getLocation();
            ChunkKey chunk = new ChunkKey(location.getWorld().getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
            ClaimEnvironmentPort.Check check = environment.check(player.getUniqueId(), chunk,
                location.getWorld().getName(), location.getBlock().getBiome().getKey().toString());
            if (!check.allowed()) return CompletableFuture.completedFuture(OperationResult.denied(check.reason()));
            return founding.create(player, name, false);
        };
        CommandPorts.PlayerDirectoryPort playerDirectory = CommandPorts.PlayerDirectoryPort.bukkit();
        ConfirmationGui confirmationGui = new ConfirmationGui();
        getServer().getPluginManager().registerEvents(confirmationGui, this);
        CommandPorts.AdminCommandPort adminPort = new AdminCommandAdapter(this, admin, invariantChecker, cache,
            catalogs.resources(), catalogs.technologies(), playerDirectory, this::reloadAdminConfiguration,
            confirmationGui, settings.plots().confirmationExpiry());
        CommandPorts.Ports ports = new CommandPorts.Ports(
            foundingPort,
            homeTeleport::teleport,
            dimensionTeleport::teleport,
            CommandPorts.ChatPort.of(civilizationChat::toggle, civilizationChat::send),
            playerDirectory,
            CommandPorts.WarCharterPort.inventoryBacked(warItems),
            adminPort
        );
        TutorialBookService tutorial = new TutorialBookService(this);
        getServer().getPluginManager().registerEvents(tutorial, this);
        CivCommand executor = new CivCommand(cache, settings, catalogs, lifecycle, territory, plots, protection,
            progression, treasury, wars, ports, this::runOnMain, confirmationGui, plotTaxes, plotTaxRuntime, tutorial);
        PluginCommand command = Objects.requireNonNull(getCommand("civ"), "plugin.yml is missing the civ command");
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private AdminModels.Result reloadAdminConfiguration() {
        reloadConfig();
        Settings.load(getConfig()); // Validate structural settings without changing live transactional policy.
        messages.load();
        return new AdminModels.Result(true, true, "Reloaded messages.yml and validated config.yml.", java.util.List.of(
            "Gameplay, database, integration, and balancing-catalog changes take effect after a full server restart."));
    }

    private void registerSellCommand(EconomyService economy) {
        SellCommand executor = new SellCommand(this, messages, economy);
        getServer().getPluginManager().registerEvents(executor, this);
        PluginCommand command = Objects.requireNonNull(getCommand("sell"), "plugin.yml is missing the sell command");
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void registerDimensionCommands() {
        for (String name : java.util.List.of("nether", "end")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "plugin.yml is missing the " + name + " command");
            command.setExecutor(dimensionTeleport);
            command.setTabCompleter(dimensionTeleport);
        }
        dimensionTeleport.start();
    }

    private void registerWildCommand() {
        wildTeleport = new WildTeleportService(this, cache, settings.travel().overworld().world());
        getServer().getPluginManager().registerEvents(wildTeleport, this);
        PluginCommand command = Objects.requireNonNull(getCommand("wild"), "plugin.yml is missing the wild command");
        command.setExecutor(wildTeleport);
        command.setTabCompleter(wildTeleport);
        wildTeleport.start();
    }

    private void registerChatCommand(CivilizationChat chat) {
        ChatCommand executor = new ChatCommand(this, chat);
        PluginCommand command = Objects.requireNonNull(getCommand("chat"), "plugin.yml is missing the chat command");
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void registerReligionCommands() {
        ReligionService service = new ReligionService(database, cache, catalogs.religion().cooldown(), settings.serverId());
        ReligionCommand executor = new ReligionCommand(this, catalogs.religion(), service);
        getServer().getPluginManager().registerEvents(executor, this);
        for (String name : java.util.List.of("religion", "sacrifice")) {
            PluginCommand command = Objects.requireNonNull(getCommand(name), "plugin.yml is missing the " + name + " command");
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    private void registerOptionalIntegrations() {
        if (getConfig().getBoolean("integrations.placeholderapi", true)) {
            placeholderApi = new PlaceholderApiIntegration(this,
                new PlaceholderValues(cache, settings, catalogs.technologies()));
            logIntegration(placeholderApi.register());
        }
        if (getConfig().getBoolean("integrations.worldguard", true)) {
            logIntegration(new WorldGuardClaimEnvironment(this, true).status());
        }
        if (getConfig().getBoolean("integrations.coreprotect", true)) {
            logIntegration(new CoreProtectIntegration(this).status());
        }
    }

    private void logIntegration(IntegrationStatus status) {
        String message = status.name() + " " + status.version() + ": " + status.detail();
        if (status.active()) getLogger().info(message); else getLogger().info("Optional integration inactive — " + message);
    }

    private void scheduleStorageMaintenance() {
        long retryTicks = toTicks(settings.database().retryDelay());
        reconnectTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!database.healthy() && !database.connecting()) connectAndWarmCache();
        }, retryTicks, retryTicks);
        cacheRefreshTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (database.healthy()) refreshCache();
        }, 1200L, 1200L);
    }

    private void connectAndWarmCache() {
        database.connectAndMigrate().thenAccept(connected -> {
            if (connected) refreshCache();
        }).exceptionally(error -> {
            getLogger().log(Level.WARNING, "MySQL startup attempt failed; retry remains scheduled.", error);
            return null;
        });
    }

    private void refreshCache() {
        if (!cacheRefreshRunning.compareAndSet(false, true)) return;
        cache.refresh().whenComplete((snapshot, error) -> {
            cacheRefreshRunning.set(false);
            if (!isEnabled()) return;
            if (error != null) {
                getLogger().log(Level.WARNING, "Civilization cache refresh failed; the previous safe snapshot remains active.", error);
            } else if (readyLogged.compareAndSet(false, true)) {
                getLogger().info("MySQL is ready and the authoritative civilization cache is loaded.");
                invariantChecker.start(this, settings.notifications().invariantCheck());
            }
        });
    }

    private void runOnMain(Runnable action) {
        if (!isEnabled()) return;
        if (Bukkit.isPrimaryThread()) action.run();
        else {
            try {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (isEnabled()) action.run();
                });
            } catch (RuntimeException ignored) {
                // An async completion may race plugin shutdown.
            }
        }
    }

    private static long toTicks(Duration duration) {
        long millis = Math.max(50L, duration.toMillis());
        if (millis > Long.MAX_VALUE / 20L) return Long.MAX_VALUE;
        return Math.max(1L, millis / 50L);
    }

    @Override
    public void onDisable() {
        cancel(reconnectTask);
        cancel(cacheRefreshTask);
        HandlerList.unregisterAll(this);
        close(invariantChecker);
        close(plotTaxRuntime);
        close(economyRecovery);
        close(establishmentRecalculator);
        close(warRuntime);
        close(wildTeleport);
        close(dimensionTeleport);
        close(homeTeleport);
        close(activityTracker);
        close(placeholderApi);
        close(founding);
        close(progression);
        if (warItems != null) warItems.unregisterRecipes();
        close(database);
        getLogger().info("Civilizations disabled.");
    }

    private static void cancel(BukkitTask task) {
        if (task != null) task.cancel();
    }

    private void close(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception failure) {
            getLogger().log(Level.WARNING, "A Civilizations component did not close cleanly.", failure);
        }
    }
}
