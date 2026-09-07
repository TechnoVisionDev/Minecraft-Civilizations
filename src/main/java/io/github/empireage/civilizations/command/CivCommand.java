package io.github.empireage.civilizations.command;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.config.CatalogManager;
import io.github.empireage.civilizations.config.Messages;
import io.github.empireage.civilizations.config.ResourceText;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.database.JsonData;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.Civilization;
import io.github.empireage.civilizations.domain.Claim;
import io.github.empireage.civilizations.domain.HomeLocation;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.domain.PlotType;
import io.github.empireage.civilizations.domain.ResearchEntry;
import io.github.empireage.civilizations.domain.ResourceKey;
import io.github.empireage.civilizations.domain.Role;
import io.github.empireage.civilizations.domain.TechnologyDefinition;
import io.github.empireage.civilizations.domain.War;
import io.github.empireage.civilizations.service.economy.TreasuryService;
import io.github.empireage.civilizations.service.economy.PlotTaxService;
import io.github.empireage.civilizations.runtime.PlotTaxRuntime;
import io.github.empireage.civilizations.runtime.TutorialBookService;
import io.github.empireage.civilizations.service.lifecycle.CivilizationLifecycleService;
import io.github.empireage.civilizations.service.lifecycle.LifecycleModels.CivilizationInfo;
import io.github.empireage.civilizations.service.lifecycle.LifecycleModels.MemberInfo;
import io.github.empireage.civilizations.service.progression.DepositService;
import io.github.empireage.civilizations.service.progression.ProgressionModule;
import io.github.empireage.civilizations.service.progression.StockpileService;
import io.github.empireage.civilizations.service.territory.PlotService;
import io.github.empireage.civilizations.service.territory.ProtectionService;
import io.github.empireage.civilizations.service.territory.TerritoryService;
import io.github.empireage.civilizations.service.war.WarService;
import io.github.empireage.civilizations.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.github.empireage.civilizations.command.CommandPorts.PlayerIdentity;

/** Complete command-first player interface for the Civilizations plugin. */
@SuppressWarnings("deprecation")
public final class CivCommand implements CommandExecutor, TabCompleter {
    private static final String PREFIX = "&8[&6Civilizations&8] &r";
    private static final int PAGE_SIZE = 10;
    private static final int STOCKPILE_HISTORY_SIZE = 10;
    private static final int MAX_CHAT_LENGTH = 256;
    private static final Set<String> ROOT_COMMANDS = Set.of(
        "help", "tutorial", "create", "info", "list", "map", "inspect", "chat",
        "invite", "accept", "deny", "members", "kick", "leave", "advisor", "transfer", "disband",
        "claim", "unclaim", "setcapital", "sethome", "home", "teleport", "plot",
        "refine", "deposit", "stockpile", "items", "customitems", "workorders", "orders", "tech", "techtree", "research", "treasury", "taxes", "war"
    );
    private static final List<String> PLOT_FLAGS = List.of(
        "public_interact", "citizen_interact", "public_containers", "citizen_containers", "redstone");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("EEE, MMM d, uuuu h:mm a z", Locale.US);

    private final StateCache cache;
    private final Settings settings;
    private final CatalogManager catalogs;
    private final CivilizationLifecycleService lifecycle;
    private final TerritoryService territory;
    private final PlotService plots;
    private final ProtectionService protection;
    private final ProgressionModule progression;
    private final TreasuryService treasury;
    private final PlotTaxService plotTaxes;
    private final PlotTaxRuntime plotTaxRuntime;
    private final TutorialBookService tutorial;
    private final WarService wars;
    private final CommandPorts.Ports ports;
    private final Consumer<Runnable> mainThread;
    private final Clock clock;
    private final CommandConfirmations confirmations;
    private final ConfirmationGui confirmationGui;
    private final ConcurrentHashMap<UUID, String> mutations = new ConcurrentHashMap<>();

    public CivCommand(StateCache cache, Settings settings, CatalogManager catalogs,
                      CivilizationLifecycleService lifecycle, TerritoryService territory,
                      PlotService plots, ProtectionService protection, ProgressionModule progression,
                      TreasuryService treasury, WarService wars, CommandPorts.Ports ports,
                      Consumer<Runnable> mainThread, ConfirmationGui confirmationGui, PlotTaxService plotTaxes, PlotTaxRuntime plotTaxRuntime,
                      TutorialBookService tutorial) {
        this(cache, settings, catalogs, lifecycle, territory, plots, protection, progression, treasury,
            wars, ports, mainThread, Clock.systemUTC(), confirmationGui, plotTaxes, plotTaxRuntime, tutorial);
    }

    CivCommand(StateCache cache, Settings settings, CatalogManager catalogs,
               CivilizationLifecycleService lifecycle, TerritoryService territory,
               PlotService plots, ProtectionService protection, ProgressionModule progression,
               TreasuryService treasury, WarService wars, CommandPorts.Ports ports,
               Consumer<Runnable> mainThread, Clock clock, ConfirmationGui confirmationGui, PlotTaxService plotTaxes, PlotTaxRuntime plotTaxRuntime,
               TutorialBookService tutorial) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.territory = Objects.requireNonNull(territory, "territory");
        this.plots = Objects.requireNonNull(plots, "plots");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.progression = Objects.requireNonNull(progression, "progression");
        this.treasury = Objects.requireNonNull(treasury, "treasury");
        this.plotTaxes = Objects.requireNonNull(plotTaxes, "plotTaxes");
        this.plotTaxRuntime = Objects.requireNonNull(plotTaxRuntime, "plotTaxRuntime");
        this.tutorial = Objects.requireNonNull(tutorial, "tutorial");
        this.wars = Objects.requireNonNull(wars, "wars");
        this.ports = Objects.requireNonNull(ports, "ports");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.confirmations = new CommandConfirmations(clock);
        this.confirmationGui = Objects.requireNonNull(confirmationGui, "confirmationGui");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        if (!sender.hasPermission("civilizations.use")) {
            send(sender, "&cYou do not have permission to use Civilizations.");
            return true;
        }
        String[] args = arguments == null ? new String[0] : arguments;
        String subcommand = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (subcommand) {
                case "help" -> help(sender, args);
                case "tutorial" -> tutorial(sender, args);
                case "create" -> create(sender, args);
                case "info" -> info(sender, args);
                case "list" -> listCivilizations(sender, args);
                case "map" -> map(sender, args);
                case "inspect" -> inspect(sender, args);
                case "chat" -> chat(sender, args);
                case "invite" -> invite(sender, args);
                case "accept" -> accept(sender, args);
                case "deny" -> deny(sender, args);
                case "members" -> members(sender, args);
                case "kick" -> kick(sender, args);
                case "leave" -> leave(sender, args);
                case "advisor" -> advisor(sender, args);
                case "transfer" -> transfer(sender, args);
                case "disband" -> disband(sender, args);
                case "claim" -> claim(sender, args);
                case "unclaim" -> unclaim(sender, args);
                case "setcapital" -> setCapital(sender, args);
                case "sethome" -> setHome(sender, args);
                case "home" -> home(sender, args);
                case "teleport" -> teleport(sender, args);
                case "plot" -> plot(sender, args);
                case "refine" -> refine(sender, args);
                case "deposit" -> deposit(sender, args);
                case "stockpile" -> stockpile(sender, args);
                case "items", "customitems" -> customItems(sender, args);
                case "workorders", "orders" -> workOrders(sender, args);
                case "tech", "techtree" -> technologyTree(sender, args);
                case "research" -> research(sender, args);
                case "treasury" -> treasury(sender, args);
                case "taxes" -> taxes(sender, args);
                case "war" -> war(sender, args);
                case "admin" -> admin(sender, args);
                default -> send(sender, "&cUnknown subcommand. Use &e/civ help&c.");
            }
        } catch (IllegalArgumentException invalid) {
            send(sender, "&c" + invalid.getMessage());
        } catch (RuntimeException unexpected) {
            send(sender, "&cThat command could not be started: " + rootMessage(unexpected));
        }
        return true;
    }

    private void tutorial(CommandSender sender, String[] args) {
        if (args.length != 1) throw usage("/civ tutorial");
        OperationResult result = tutorial.give(player(sender));
        send(sender, (result.success() ? "&e" : "&c") + result.message());
    }

    private void help(CommandSender sender, String[] args) {
        if (args.length > 2) throw usage("/civ help [page | topic]");
        String requested = args.length == 2 ? args[1].toLowerCase(Locale.ROOT) : "1";
        int page = switch (requested) {
            case "overview", "general", "start", "help" -> 1;
            case "membership", "members", "invite", "roles" -> 2;
            case "territory", "claim", "map", "inspect", "home" -> 3;
            case "plot", "plots" -> 4;
            case "resources", "items", "refine", "deposit", "stockpile", "workorders", "orders" -> 5;
            case "research", "tech", "techtree" -> 6;
            case "treasury", "economy", "taxes" -> 7;
            case "war", "diplomacy" -> 8;
            case "admin" -> {
                if (sender.hasPermission("civilizations.admin")) {
                    send(sender, "&6Civilizations administration");
                    line(sender, "&7Use &e/civ admin help&7 for the complete operator guide.");
                } else send(sender, "&cYou do not have administrator permission.");
                yield -1;
            }
            default -> {
                try {
                    yield CommandInput.positiveInt(requested, "Help page");
                } catch (IllegalArgumentException invalid) {
                    yield 0;
                }
            }
        };
        if (page < 0) return;
        List<List<String>> pages = helpPages(sender);
        if (page < 1 || page > pages.size()) {
            send(sender, "&cChoose help page 1-" + pages.size()
                + " or a topic: membership, territory, plots, resources, research, treasury, war.");
            return;
        }
        send(sender, "&6Civilizations Guide &7(page " + page + "/" + pages.size() + ")");
        pages.get(page - 1).forEach(value -> line(sender, value));
        if (page > 1) line(sender, "&8Previous: &e/civ help " + (page - 1));
        if (page < pages.size()) line(sender, "&8Next: &e/civ help " + (page + 1));
        line(sender, "&8Topics: &7membership, territory, plots, resources, research, treasury, war");
    }

    private List<List<String>> helpPages(CommandSender sender) {
        String status = "&7Begin with &e/civ list&7 and &e/civ info <name>&7 to explore existing civilizations.";
        if (sender instanceof Player player) {
            Member member = cache.snapshot().member(player.getUniqueId());
            status = member == null
                ? "&7You are unaffiliated: found one with &e/civ create <name>&7 or accept an invitation."
                : "&7You are a &f" + title(member.role()) + "&7. Higher roles can manage more civic systems.";
        }
        return List.of(
            List.of(
                "&eWelcome! &7Civilizations combines membership, territory, resources, research, trade, and war.",
                status,
                "&e/civ tutorial &8— &7receive the tutorial book; one copy per 24 hours, including your welcome copy.",
                "&e/civ create <name> &8— &7preview founding costs and confirm in the green/red menu.",
                "&e/civ list [page] &8— &7browse civilizations; &e/civ info [name] &8— &7see full statistics.",
                "&e/civ chat [message] &8— &7toggle civ chat or send one civ-only message.",
                "&7Civilization names are the only identifiers and matching is not case-sensitive."),
            List.of(
                "&eMembership tutorial &8— &7Leaders govern; advisors assist; citizens contribute.",
                "&e/civ invite <player> &8— &7leader/advisor invites an unaffiliated player.",
                "&e/civ accept <name>&7 or &e/civ deny <name> &8— &7respond to an invitation.",
                "&e/civ members [page] &8— &7view roles and establishment progress.",
                "&e/civ advisor <add | remove> <player> &8— &7leader manages advisors.",
                "&e/civ kick <player>&7, &eleave&7, &etransfer <player>&7, &edisband &8— &7manage departures and leadership.",
                "&7Property-affecting kicks and disbanding require the green/red confirmation menu."),
            List.of(
                "&eTerritory tutorial &8— &7Claims must obey world, distance, capacity, and adjacency rules.",
                "&e/civ map [radius] &8— &7draw nearby claims; &e/civ inspect &8— &7explain the current chunk.",
                "&e/civ claim &8— &7preview and claim this chunk; leader/advisor.",
                "&e/civ unclaim &8— &7release an eligible non-capital claim; leader/advisor.",
                "&e/civ setcapital &8— &7move the capital here; leader only.",
                "&e/civ sethome&7 and &e/civ home &8— &7set the civic home and teleport there.",
                "&e/civ teleport <overworld | nether | end> &8— &7dimension travel; Overworld returns to its arrival point.",
                "&e/nether&7 and &e/end &8— &7safe dimension wilderness; expedition tech and a ritual offering required.",
                "&e/nether recipe&7 and &e/end recipe &8— &7craft the offering consumed for each successful trip.",
                "&e/wild &8— &7random safe Overworld wilderness; 12-hour cooldown, free first-join placement.",
                "&7Costly territory changes open a confirmation menu and recheck conditions when accepted."),
            List.of(
                "&ePlot tutorial &8— &7Stand in a claimed chunk before running plot commands.",
                "&e/civ plot type <civic | common> &8— &7choose public civic use or purchasable common tenure.",
                "&e/civ plot defaultprice <amount>&7, &elist [price]&7, &eunlist &8— &7manage public listings.",
                "&e/civ plot buy&7, &esell <price>&7, &esurrender &8— &7manage private tenure.",
                "&e/civ plot trust <player>&7 or &euntrust <player> &8— &7share a private plot.",
                "&e/civ plot label <text | clear>&7, &egreeting <text | clear> &8— &7customize entry messages.",
                "&e/civ plot flags [flag] [on | off] &8— &7inspect or edit interaction permissions."),
            List.of(
                "&eResources tutorial &8— &7Gather civic materials, refine them, then contribute to shared goals.",
                "&e/civ items &8— &7browse every custom item by tier; click an item for its recipe.",
                "&e/civ refine &8— &7convert eligible materials using the configured refining rules.",
                "&e/civ deposit <hand | all> &8— &7move authentic civic items into the stockpile.",
                "&e/civ stockpile [history [page]] &8— &7open the stockpile GUI or inspect the contribution ledger.",
                "&e/civ workorders (or /civ orders) &8— &7compare persistent orders with shared stockpile balances.",
                "&7Everyone can deposit; leaders/advisors click ready orders to spend their requirements.",
                "&e/religion &8— &7inspect Greek gods, favor, offerings, blessings, and cooldowns."),
            List.of(
                "&eResearch tutorial &8— &7Knowledge and prerequisites unlock permanent civilization advances.",
                "&e/civ tech &8— &7open the technology tree, inspect prerequisites, and select active research.",
                "&e/civ research status &8— &7show active projects and completion times in chat.",
                "&e/civ research start <technology> &8— &7start an eligible project; leader/advisor.",
                "&e/civ research cancel [slot] &8— &7cancel active research; leader/advisor.",
                "&7Locked technology entries explain missing prerequisites or Knowledge."),
            List.of(
                "&eTreasury tutorial &8— &7The treasury funds shared civilization actions.",
                "&e/civ taxes [set <amount>] &8— &7weekly private-plot taxes; only the leader sets the rate.",
                "&e/civ treasury balance &8— &7view the current balance.",
                "&e/civ treasury deposit <amount> &8— &7contribute currency.",
                "&e/civ treasury withdraw <amount> &8— &7leader/advisor withdrawal.",
                "&e/civ treasury history [page] &8— &7review the auditable transaction ledger.",
                "&7Founding, claims, plots, and other costs show exact values before confirmation."),
            List.of(
                "&eWar tutorial &8— &7Only leaders can declare a campaign against another active civilization.",
                "&e/civ war status [name] &8— &7inspect war timing, opponent, and roster.",
                "&e/civ war declare <name> &8— &7preview the charter and stockpile cost, then confirm in the GUI.",
                "&7During war, participants may fight, break enemy territory, and loot containers.",
                "&e/civ war peace &8— &7offer or accept peace; &e/civ war cancel &8— &7cancel when rules allow.",
                "&7Campaigns freeze membership and apply scheduled combat/territory rules.",
                sender.hasPermission("civilizations.admin")
                    ? "&7Operators: use &e/civ admin help&7 for diagnostics and repair commands."
                    : "&7Ask a server operator for help if campaign state appears inconsistent.")
        );
    }

    private void create(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (!player.hasPermission("civilizations.create")) {
            send(player, "&cYou do not have permission to found a civilization.");
            return;
        }
        if (args.length < 2) throw usage("/civ create <name>");
        String name = CommandInput.join(args, 1);
        String fingerprint = name + "\u0000" + chunk(player).compact() + "\u0000"
            + JsonData.costs(settings.founding().materialCost()) + "\u0000" + settings.founding().moneyCost().toPlainString();
        String confirmationAction = "founding-confirm";
        Instant expires = clock.instant().plus(settings.plots().confirmationExpiry());
        confirmations.remember(player.getUniqueId(), confirmationAction, fingerprint, expires);
        send(player, "&eFounding &f" + name + "&e will consume exactly &f"
            + formatResources(settings.founding().materialCost()) + "&e and &f"
            + settings.founding().moneyCost().toPlainString() + "&e money, and claim this chunk as its capital.");
        confirmationGui.open(player, "Found " + name, List.of(
            "&7Civilization: &f" + name,
            "&7Materials: &f" + formatResources(settings.founding().materialCost()),
            "&7Money: &f" + settings.founding().moneyCost().toPlainString(),
            "&7This chunk becomes the capital."
        ), expires, () -> {
            if (!fingerprint.equals(confirmations.consume(player.getUniqueId(), confirmationAction))) {
                send(player, "&cThat founding confirmation expired or no longer matches.");
                return;
            }
            mutate(player, "founding", () -> ports.founding().create(player, name));
        }, () -> confirmations.invalidate(player.getUniqueId(), confirmationAction));
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length == 1) {
            Player player = player(sender);
            Member member = requireMembership(player);
            readFuture(sender, () -> lifecycle.info(member.civilizationId()), (audience, value) -> {
                if (value.isEmpty()) send(audience, "&cYour civilization is unavailable.");
                else renderInfo(audience, value.get());
            });
            return;
        }
        String name = CommandInput.join(args, 1);
        readFuture(sender, () -> lifecycle.info(name), (audience, value) -> {
            if (value.isEmpty()) send(audience, "&cNo civilization matches that name.");
            else renderInfo(audience, value.get());
        });
    }

    private void renderInfo(CommandSender sender, CivilizationInfo info) {
        StateSnapshot state = cache.snapshot();
        Set<String> unlocked = state.technologies(info.id());
        String age = catalogs.technologies().age(unlocked);
        String leader = info.members().stream().filter(member -> member.playerId().equals(info.leaderId()))
            .map(MemberInfo::lastKnownName).findFirst().orElse(info.leaderId().toString());
        send(sender, "&6" + info.name() + " &7— &f" + age);
        line(sender, "&7Leader: &f" + leader + " &8| &7Status: &f" + title(info.status().name()));
        line(sender, "&7Members: &f" + info.members().size() + " &8(&f" + info.establishedNonLeaders()
            + " established non-leaders&8) &7Advisors: &f" + info.advisorCount() + "/" + info.advisorLimit());
        line(sender, "&7Claims: &f" + info.claimCount() + "/" + info.claimCapacity()
            + " &8| &7Knowledge: &f" + info.knowledge() + " &8| &7Treasury: &f" + info.treasury().toPlainString());
        int memberCapacity = info.establishedNonLeaders() * settings.claims().establishedMemberBonus();
        int researchCapacity = unlocked.stream().map(catalogs.technologies()::get).filter(Objects::nonNull)
            .mapToInt(technology -> technology.modifiers().getOrDefault("claim-capacity", 0)).sum();
        Civilization cachedCivilization = state.civilization(info.id());
        int adminCapacity = cachedCivilization == null ? 0 : cachedCivilization.adminClaimBonus();
        long rawCapacity = (long) settings.claims().baseCapacity() + memberCapacity + researchCapacity + adminCapacity;
        line(sender, "&7Claim capacity: &f" + settings.claims().baseCapacity() + " base + " + memberCapacity
            + " established members + " + researchCapacity + " research + " + adminCapacity + " administrator bonus"
            + (rawCapacity > settings.claims().absoluteCap() ? " &8(capped at " + settings.claims().absoluteCap() + ")" : ""));
        if (info.peaceShieldUntil() != null && info.peaceShieldUntil().isAfter(clock.instant())) {
            line(sender, "&7Peace shield: &f" + formatTime(info.peaceShieldUntil(), settings.war().zone()));
        }
        List<ResearchEntry> active = state.research(info.id());
        if (active.isEmpty()) line(sender, "&7Research: &fNo active project");
        else active.stream().sorted(Comparator.comparingInt(ResearchEntry::slot)).forEach(entry ->
            line(sender, "&7Research #" + entry.slot() + ": &f" + technologyName(entry.technologyKey())
                + " &8— &f" + formatTime(entry.completesAt(), settings.war().zone())));
        WarService.WarStatus war = warStatus(info.id());
        if (war == null) line(sender, "&7Diplomacy: &fAt peace");
        else line(sender, "&7Diplomacy: &f" + title(war.war().effectiveState(clock.instant()).name()) + " with &c"
            + (war.opponent() == null ? "Unknown" : war.opponent().name()));
    }

    private void listCivilizations(CommandSender sender, String[] args) {
        if (args.length > 2) throw usage("/civ list [page]");
        int page = args.length == 2 ? CommandInput.positiveInt(args[1], "Page") : 1;
        readFuture(sender, lifecycle::list, (audience, entries) -> {
            int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page > pages) {
                send(audience, "&cThere are only " + pages + " civilization list page(s).");
                return;
            }
            send(audience, "&6Civilizations &7(page " + page + "/" + pages + ")");
            if (entries.isEmpty()) line(audience, "&7No active civilizations have been founded.");
            entries.stream().skip((long) (page - 1) * PAGE_SIZE).limit(PAGE_SIZE).forEach(entry ->
                line(audience, "&e" + entry.name() + " &7— " + entry.members()
                    + " members, " + entry.claims() + " claims" + (entry.atWar() ? " &c[WAR]" : "")));
        });
    }

    private void map(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length > 2) throw usage("/civ map [radius]");
        int radius = args.length == 2 ? CommandInput.positiveInt(args[1], "Radius") : 4;
        if (radius > 10) throw new IllegalArgumentException("Map radius cannot exceed 10 chunks.");
        ChunkKey center = chunk(player);
        TerritoryService.MapView view = territory.map(player.getUniqueId(), center, radius);
        if (!view.cacheReady()) {
            send(player, "&cTerritory data is still loading.");
            return;
        }

        Map<ChunkKey, TerritoryService.MapCell> cells = new LinkedHashMap<>();
        view.cells().forEach(cell -> cells.put(cell.chunk(), cell));
        send(player, "&6Territory map &7(center " + center.x() + ", " + center.z() + ")");
        for (int z = center.z() - radius; z <= center.z() + radius; z++) {
            StringBuilder row = new StringBuilder();
            for (int x = center.x() - radius; x <= center.x() + radius; x++) {
                TerritoryService.MapCell cell = cells.get(new ChunkKey(center.worldId(), x, z));
                char marker = cell.current() ? '@' : cell.marker();
                row.append(mapColor(marker)).append(marker).append(' ');
            }
            line(player, row.toString());
        }
        line(player, "&f@ current  &6K capital  &eC civic  &aM common  &b$ sale  &dP private  &cX other  &7. wild");
        TerritoryService.Inspection current = territory.inspect(player.getUniqueId(), center);
        line(player, "&7Current: &f" + (current.claimed() ? current.civilizationName() + " " + title(current.plotType().name()) : "Wilderness"));
    }

    private void inspect(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length != 1) throw usage("/civ inspect");
        ChunkKey key = chunk(player);
        TerritoryService.Inspection inspection = territory.inspect(player.getUniqueId(), key);
        if (!inspection.cacheReady()) {
            send(player, "&cTerritory data is still loading; protection is fail-closed.");
            return;
        }
        send(player, "&6Chunk inspection &7(" + key.x() + ", " + key.z() + ")");
        if (!inspection.claimed()) {
            line(player, "&7Owner: &fWilderness");
            line(player, "&7Build and interaction: &aAllowed by civilization protection");
            return;
        }
        line(player, "&7Owner: &f" + inspection.civilizationName() + " &8| &7Plot: &f" + title(inspection.plotType().name()));
        line(player, "&7Controller: &f" + inspection.control() + (inspection.ownCivilization() ? " &a(your civilization)" : ""));
        Material held = player.getInventory().getItemInMainHand().getType();
        var targeted = player.getTargetBlockExact(5);
        Material targetedMaterial = targeted == null ? null : targeted.getType();
        renderDecision(player, "Break", protection.authorize(player.getUniqueId(), key, ProtectionService.Action.BREAK, targetedMaterial, false));
        renderDecision(player, "Place held block", protection.authorize(player.getUniqueId(), key, ProtectionService.Action.PLACE, held, false));
        renderDecision(player, "Interact", protection.authorize(player.getUniqueId(), key, ProtectionService.Action.INTERACT, held, false));
        renderDecision(player, "Containers", protection.authorize(player.getUniqueId(), key, ProtectionService.Action.CONTAINER, held, false));
    }

    private void renderDecision(CommandSender sender, String label, ProtectionService.Decision decision) {
        line(sender, "&7" + label + ": " + (decision.allowed() ? "&aAllowed" : "&cDenied") + " &8— &7" + decision.reason());
    }

    private void chat(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (!player.hasPermission("civilizations.chat")) {
            send(player, "&cYou do not have permission to use civilization chat.");
            return;
        }
        if (args.length == 1) {
            mutate(player, "chat-toggle", () -> ports.chat().toggle(player));
            return;
        }
        String message = CommandInput.join(args, 1);
        if (message.isBlank()) throw new IllegalArgumentException("Civilization chat messages cannot be blank.");
        if (message.length() > MAX_CHAT_LENGTH) throw new IllegalArgumentException("Civilization chat messages cannot exceed " + MAX_CHAT_LENGTH + " characters.");
        OperationResult result = ports.chat().send(player, message);
        if (!result.success() || !result.message().isBlank()) outcome(player, result);
    }

    private void invite(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length != 2) throw usage("/civ invite <player>");
        PlayerIdentity target = ports.players().findKnownExact(args[1]).orElseThrow(
            () -> new IllegalArgumentException("No known player matches '" + args[1] + "'. They must have joined this server before."));
        mutate(player, "invite", () -> lifecycle.invite(player.getUniqueId(), target.id(), target.name()));
    }

    private void accept(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length < 2) throw usage("/civ accept <civilization>");
        String civilization = CommandInput.join(args, 1);
        mutate(player, "accept-invitation", () -> lifecycle.accept(player.getUniqueId(), civilization));
    }

    private void deny(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length < 2) throw usage("/civ deny <civilization>");
        String civilization = CommandInput.join(args, 1);
        mutate(player, "deny-invitation", () -> lifecycle.deny(player.getUniqueId(), civilization));
    }

    private void members(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length > 2) throw usage("/civ members [page]");
        int page = args.length == 2 ? CommandInput.positiveInt(args[1], "Page") : 1;
        Member membership = requireMembership(player);
        readFuture(player, () -> lifecycle.info(membership.civilizationId()), (audience, value) -> {
            if (value.isEmpty()) {
                send(audience, "&cYour civilization roster is unavailable.");
                return;
            }
            List<MemberInfo> roster = value.get().members();
            int pages = Math.max(1, (roster.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page > pages) {
                send(audience, "&cThere are only " + pages + " roster page(s).");
                return;
            }
            send(audience, "&6" + value.get().name() + " roster &7(page " + page + "/" + pages + ")");
            roster.stream().skip((long) (page - 1) * PAGE_SIZE).limit(PAGE_SIZE).forEach(member -> {
                String activity = member.established() ? "&aEstablished" : "&eEstablishing";
                String locked = member.membershipLocked() ? " &c[WAR LOCKED]" : "";
                line(audience, "&e" + member.lastKnownName() + " &8— &f" + title(member.role()) + " &8| " + activity
                    + " &8| &7last active " + TimeUtil.relative(member.lastActiveAt(), clock.instant()) + locked);
            });
        });
    }

    private void kick(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length != 2) throw usage("/civ kick <player>");
        Member actor = requireMembership(player);
        if (!actor.role().atLeast(Role.ADVISOR)) {
            send(player, "&cOnly leaders and advisors may kick citizens.");
            return;
        }
        Member target = memberByName(actor.civilizationId(), args[1]).orElseThrow(
            () -> new IllegalArgumentException("That player is not a member of your civilization."));
        if (target.role() != Role.CITIZEN) {
            send(player, "&cOnly ordinary citizens can be kicked.");
            return;
        }
        String action = "kick:" + target.playerId();
        List<Claim> targetPlots = cache.snapshot().claims(actor.civilizationId()).stream()
            .filter(claim -> target.playerId().equals(claim.plotOwnerId()))
            .sorted(Comparator.comparingLong(Claim::id)).toList();
        String fingerprint = kickFingerprint(target, targetPlots);
        long privatePlots = targetPlots.size();
        if (privatePlots > 0) {
            Instant expires = clock.instant().plus(settings.plots().confirmationExpiry());
            confirmations.remember(player.getUniqueId(), action, fingerprint, expires);
            send(player, "&eRemoving " + target.lastKnownName() + " will terminate " + privatePlots
                + " private plot right(s) without refund.");
            confirmationGui.open(player, "Confirm citizen removal", List.of(
                "&7Citizen: &f" + target.lastKnownName(),
                "&cTerminates " + privatePlots + " private plot right(s).",
                "&cNo refund will be issued."
            ), expires, () -> mutate(player, "kick", () -> {
                String confirmedState = confirmations.consume(player.getUniqueId(), action);
                if (!fingerprint.equals(confirmedState)) return CompletableFuture.completedFuture(
                    OperationResult.denied("That kick confirmation expired or the member's property changed."));
                Set<Long> confirmedPlots = targetPlots.stream().map(Claim::id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
                return lifecycle.kick(player.getUniqueId(), target.playerId(), confirmedPlots);
            }), () -> confirmations.invalidate(player.getUniqueId(), action));
            return;
        }
        mutate(player, "kick", () -> lifecycle.kick(player.getUniqueId(), target.playerId(), Set.of()));
    }

    private void leave(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length != 1) throw usage("/civ leave");
        mutate(player, "leave", () -> lifecycle.leave(player.getUniqueId()));
    }

    private void advisor(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length != 3 || !(args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
            throw usage("/civ advisor <add | remove> <player>");
        }
        Member actor = requireMembership(player);
        if (actor.role() != Role.LEADER) {
            send(player, "&cOnly the civilization leader may manage advisors.");
            return;
        }
        Member target = memberByName(actor.civilizationId(), args[2]).orElseThrow(
            () -> new IllegalArgumentException("That player is not a member of your civilization."));
        if (args[1].equalsIgnoreCase("add")) {
            mutate(player, "advisor-add", () -> lifecycle.promoteAdvisor(player.getUniqueId(), target.playerId()));
        } else {
            mutate(player, "advisor-remove", () -> lifecycle.demoteAdvisor(player.getUniqueId(), target.playerId()));
        }
    }

    private void transfer(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length != 2) throw usage("/civ transfer <player>");
        Member actor = requireMembership(player);
        if (actor.role() != Role.LEADER) {
            send(player, "&cOnly the civilization leader may transfer leadership.");
            return;
        }
        Member target = memberByName(actor.civilizationId(), args[1]).orElseThrow(
            () -> new IllegalArgumentException("That player is not a member of your civilization."));
        mutate(player, "leadership-transfer", () -> lifecycle.transferLeadership(player.getUniqueId(), target.playerId()));
    }

    private void disband(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length != 1) throw usage("/civ disband");
        Member member = requireMembership(player);
        if (member.role() != Role.LEADER) {
            send(player, "&cOnly the civilization leader may disband it.");
            return;
        }
        Civilization civilization = cache.snapshot().civilization(member.civilizationId());
        Instant expires = clock.instant().plus(settings.plots().confirmationExpiry());
        send(player, "&cDisbanding archives the civilization, releases every claim, ends every membership and plot right,");
        line(player, "&cand cancels active research. Review the confirmation menu carefully.");
        confirmationGui.open(player, "Disband " + civilization.name(), List.of(
            "&cThis permanently archives " + civilization.name() + ".",
            "&cAll claims and memberships end.",
            "&cAll plot rights and research end."
        ), expires, () -> mutateFuture(player, "disband",
            () -> lifecycle.disband(player.getUniqueId(), civilization.name()), (audience, result) -> {
                outcome(audience, result.result());
                if (result.result().success() && result.treasuryPayout().signum() > 0) {
                    line(audience, "&7Treasury payout: &f" + result.treasuryPayout().toPlainString()
                        + (result.payoutRecoveryOperationId() == null ? ""
                        : " &8(recovery " + result.payoutRecoveryOperationId() + ")"));
                }
            }), () -> {});
    }

    private void claim(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length != 1) throw usage("/civ claim");
        Location location = player.getLocation();
        String biome = location.getBlock().getBiome().name();
        TerritoryService.ClaimSite site = new TerritoryService.ClaimSite(chunk(location), location.getWorld().getName(), biome);
        String action = "claim";
        TerritoryService.Preparation prepared = territory.prepareClaim(player.getUniqueId(), site);
        if (!prepared.validation().success()) {
            outcome(player, prepared.validation());
            return;
        }
        confirmations.remember(player.getUniqueId(), action, prepared.confirmation().token(), prepared.confirmation().expiresAt());
        send(player, "&e" + prepared.validation().message());
        confirmationGui.open(player, "Confirm land claim", List.of(
            "&7Chunk: &f" + site.chunk().x() + ", " + site.chunk().z(),
            "&7World: &f" + site.worldName(),
            "&7The exact cost shown in chat will be charged."
        ), prepared.confirmation().expiresAt(), () -> mutate(player, action, () -> {
            String token = confirmations.consume(player.getUniqueId(), action);
            return token == null ? CompletableFuture.completedFuture(OperationResult.denied(
                "That claim confirmation expired. Run /civ claim again."))
                : territory.claim(player.getUniqueId(), site, token);
        }), () -> confirmations.invalidate(player.getUniqueId(), action));
    }

    private void unclaim(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length != 1) throw usage("/civ unclaim");
        ChunkKey target = chunk(player);
        String action = "unclaim";
        TerritoryService.Preparation prepared = territory.prepareUnclaim(player.getUniqueId(), target);
        if (!prepared.validation().success()) {
            outcome(player, prepared.validation());
            return;
        }
        confirmations.remember(player.getUniqueId(), action, prepared.confirmation().token(), prepared.confirmation().expiresAt());
        send(player, "&e" + prepared.validation().message());
        confirmationGui.open(player, "Confirm land release", List.of(
            "&7Chunk: &f" + target.x() + ", " + target.z(),
            "&cThis releases the claim to wilderness."
        ), prepared.confirmation().expiresAt(), () -> mutate(player, "unclaim", () -> {
            String token = confirmations.consume(player.getUniqueId(), action);
            return token == null
                ? CompletableFuture.completedFuture(OperationResult.denied(
                    "That unclaim confirmation expired. Run /civ unclaim again."))
                : territory.unclaim(player.getUniqueId(), target, token);
        }), () -> confirmations.invalidate(player.getUniqueId(), action));
    }

    private void setCapital(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length != 1) throw usage("/civ setcapital");
        Location location = player.getLocation().clone();
        ChunkKey target = chunk(player);
        String action = "setcapital";
        TerritoryService.Preparation prepared = territory.prepareCapitalMove(player.getUniqueId(), target);
        if (!prepared.validation().success()) {
            outcome(player, prepared.validation());
            return;
        }
        confirmations.remember(player.getUniqueId(), action, prepared.confirmation().token(), prepared.confirmation().expiresAt());
        send(player, "&e" + prepared.validation().message());
        confirmationGui.open(player, "Confirm capital move", List.of(
            "&7New capital chunk: &f" + target.x() + ", " + target.z(),
            "&cThe current capital designation will move."
        ), prepared.confirmation().expiresAt(), () -> mutate(player, "capital-move", () -> {
            String token = confirmations.consume(player.getUniqueId(), action);
            HomeLocation newHome = new HomeLocation(location.getWorld().getUID(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
            return token == null
                ? CompletableFuture.completedFuture(OperationResult.denied(
                    "That capital-move confirmation expired. Run /civ setcapital again."))
                : territory.moveCapital(player.getUniqueId(), target, newHome, token);
        }), () -> confirmations.invalidate(player.getUniqueId(), action));
    }

    private void setHome(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length != 1) throw usage("/civ sethome");
        Location location = player.getLocation();
        HomeLocation home = new HomeLocation(location.getWorld().getUID(), location.getX(), location.getY(), location.getZ(),
            location.getYaw(), location.getPitch());
        mutate(player, "set-home", () -> territory.setHome(player.getUniqueId(), home));
    }

    private void home(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length != 1) throw usage("/civ home");
        outcome(player, ports.home().teleport(player));
    }

    private void teleport(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length != 2) throw usage("/civ teleport <overworld | nether | end>");
        outcome(player, ports.travel().teleport(player, args[1]));
    }

    private void plot(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length < 2) throw usage("/civ plot <type | defaultprice | list | unlist | buy | sell | surrender | trust | untrust | label | greeting | flags>");
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "type" -> plotType(player, args);
            case "defaultprice" -> plotDefaultPrice(player, args);
            case "list" -> plotList(player, args);
            case "unlist" -> plotUnlist(player, args);
            case "buy" -> plotBuy(player, args);
            case "sell" -> plotSell(player, args);
            case "surrender" -> plotSurrender(player, args);
            case "trust" -> plotTrust(player, args, true);
            case "untrust" -> plotTrust(player, args, false);
            case "label" -> plotMetadata(player, args, true);
            case "greeting" -> plotMetadata(player, args, false);
            case "flags" -> plotFlags(player, args);
            default -> throw usage("/civ plot <type | defaultprice | list | unlist | buy | sell | surrender | trust | untrust | label | greeting | flags>");
        }
    }

    private void plotType(Player player, String[] args) {
        if (args.length != 3) throw usage("/civ plot type <civic | common>");
        PlotType type = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "civic" -> PlotType.CIVIC;
            case "common" -> PlotType.COMMON;
            default -> throw new IllegalArgumentException("Plot type must be civic or common.");
        };
        mutate(player, "plot-type", () -> plots.setPublicType(player.getUniqueId(), chunk(player), type));
    }

    private void plotDefaultPrice(Player player, String[] args) {
        if (args.length != 3) throw usage("/civ plot defaultprice <amount>");
        BigDecimal price = CommandInput.currency(args[2], true);
        mutate(player, "plot-default-price", () -> plots.setDefaultPrice(player.getUniqueId(), price));
    }

    private void plotList(Player player, String[] args) {
        if (args.length > 3) throw usage("/civ plot list [price]");
        BigDecimal price = args.length == 3 ? CommandInput.currency(args[2], true) : null;
        mutate(player, "plot-list", () -> plots.listPublicPlot(player.getUniqueId(), chunk(player), price));
    }

    private void plotUnlist(Player player, String[] args) {
        if (args.length != 2) throw usage("/civ plot unlist");
        mutate(player, "plot-unlist", () -> plots.unlist(player.getUniqueId(), chunk(player)));
    }

    private void plotBuy(Player player, String[] args) {
        if (args.length != 2) throw usage("/civ plot buy");
        ChunkKey target = chunk(player);
        Member member = requireMembership(player);
        readFuture(player, () -> plotTaxes.rate(member.civilizationId()), (audience, rate) -> {
            if (player.isOnline()) confirmPlotBuy(player, target, rate);
        });
    }

    private void confirmPlotBuy(Player player, ChunkKey target, BigDecimal weeklyTax) {
        String action = "plot-buy";
        PlotService.PurchasePreparation prepared = plots.preparePurchase(player.getUniqueId(), target);
        if (!prepared.validation().success()) {
            outcome(player, prepared.validation());
            return;
        }
        confirmations.remember(player.getUniqueId(), action, prepared.confirmation().token(), prepared.confirmation().expiresAt());
        send(player, "&ePurchase price: &f" + prepared.exactPrice().toPlainString());
        line(player, "&c" + prepared.leaseWarning());
        confirmationGui.open(player, "Confirm plot purchase", List.of(
            "&7Price: &f" + prepared.exactPrice().toPlainString(),
            "&7Chunk: &f" + target.x() + ", " + target.z(),
            "&7Weekly plot tax: &f$" + weeklyTax.toPlainString(),
            "&7First automatic payment is due in seven days.",
            "&cInsufficient funds returns this plot to the civilization.",
            "&c" + prepared.leaseWarning()
        ), prepared.confirmation().expiresAt(), () -> mutate(player, "plot-buy", () -> {
            String token = confirmations.consume(player.getUniqueId(), action);
            return token == null
                ? CompletableFuture.completedFuture(OperationResult.denied(
                    "That plot-purchase confirmation expired. Run /civ plot buy again."))
                : plots.purchase(player.getUniqueId(), target, token, weeklyTax);
        }), () -> confirmations.invalidate(player.getUniqueId(), action));
    }

    private void plotSell(Player player, String[] args) {
        if (args.length != 3) throw usage("/civ plot sell <price>");
        BigDecimal price = CommandInput.currency(args[2], true);
        mutate(player, "plot-sell", () -> plots.listOwnedPlot(player.getUniqueId(), chunk(player), price));
    }

    private void plotSurrender(Player player, String[] args) {
        if (args.length != 2) throw usage("/civ plot surrender");
        ChunkKey target = chunk(player);
        String action = "plot-surrender";
        PlotService.SurrenderPreparation prepared = plots.prepareSurrender(player.getUniqueId(), target);
        if (!prepared.validation().success()) {
            outcome(player, prepared.validation());
            return;
        }
        confirmations.remember(player.getUniqueId(), action, prepared.confirmation().token(), prepared.confirmation().expiresAt());
        send(player, "&c" + prepared.warning());
        confirmationGui.open(player, "Confirm plot surrender", List.of(
            "&7Chunk: &f" + target.x() + ", " + target.z(),
            "&c" + prepared.warning(),
            "&cNo refund will be issued."
        ), prepared.confirmation().expiresAt(), () -> mutate(player, "plot-surrender", () -> {
            String token = confirmations.consume(player.getUniqueId(), action);
            return token == null
                ? CompletableFuture.completedFuture(OperationResult.denied(
                    "That plot-surrender confirmation expired. Run /civ plot surrender again."))
                : plots.surrender(player.getUniqueId(), target, token);
        }), () -> confirmations.invalidate(player.getUniqueId(), action));
    }

    private void plotTrust(Player player, String[] args, boolean trust) {
        if (args.length != 3) throw usage("/civ plot " + (trust ? "trust" : "untrust") + " <player>");
        Member actor = requireMembership(player);
        Member target = memberByName(actor.civilizationId(), args[2]).orElseThrow(
            () -> new IllegalArgumentException("That player is not a member of your civilization."));
        if (trust) mutate(player, "plot-trust", () -> plots.trust(player.getUniqueId(), chunk(player), target.playerId()));
        else mutate(player, "plot-untrust", () -> plots.untrust(player.getUniqueId(), chunk(player), target.playerId()));
    }

    private void plotFlags(Player player, String[] args) {
        if (args.length == 2) {
            Claim claim = cache.snapshot().claim(chunk(player));
            if (claim == null) {
                send(player, "&cThis chunk is wilderness.");
                return;
            }
            send(player, "&6Plot flags for chunk " + claim.key().x() + ", " + claim.key().z());
            for (String flag : PLOT_FLAGS) {
                boolean enabled = claim.flags().getOrDefault(flag, false);
                line(player, "&7" + flag + ": " + (enabled ? "&aON" : "&cOFF"));
            }
            line(player, "&7Edit with &f/civ plot flags <flag> <on | off>&7.");
            return;
        }
        if (args.length != 4 || !PLOT_FLAGS.contains(args[2].toLowerCase(Locale.ROOT))) {
            throw usage("/civ plot flags <" + String.join(" | ", PLOT_FLAGS) + "> <on | off>");
        }
        boolean enabled = switch (args[3].toLowerCase(Locale.ROOT)) {
            case "on", "true", "enable", "enabled" -> true;
            case "off", "false", "disable", "disabled" -> false;
            default -> throw new IllegalArgumentException("Choose on or off.");
        };
        String flag = args[2].toLowerCase(Locale.ROOT);
        mutate(player, "plot-flags", () -> plots.setFlag(player.getUniqueId(), chunk(player), flag, enabled));
    }

    private void plotMetadata(Player player, String[] args, boolean label) {
        if (args.length < 3) throw usage("/civ plot " + (label ? "label" : "greeting") + " <text | clear>");
        String value = CommandInput.join(args, 2);
        if (value.equalsIgnoreCase("clear")) value = "";
        String finalValue = value;
        mutate(player, label ? "plot-label" : "plot-greeting", () -> label
            ? plots.setHomeLabel(player.getUniqueId(), chunk(player), finalValue)
            : plots.setGreeting(player.getUniqueId(), chunk(player), finalValue));
    }

    private void refine(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length != 1) throw usage("/civ refine");
        progression.refining().open(player);
    }

    private void deposit(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length != 2) throw usage("/civ deposit <hand | all>");
        DepositService.Scope scope = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "hand" -> DepositService.Scope.HAND;
            case "all" -> DepositService.Scope.ALL;
            default -> throw new IllegalArgumentException("Deposit scope must be hand or all.");
        };
        mutateFuture(player, "deposit", () -> progression.deposits().deposit(player, scope), (audience, result) -> {
            outcome(audience, new OperationResult(result.success(), result.message()));
        });
    }

    private void taxes(CommandSender sender, String[] args) {
        Player player = player(sender);
        Member member = requireMembership(player);
        if (args.length == 1) { plotTaxRuntime.open(player); return; }
        if (args.length != 3 || !args[1].equalsIgnoreCase("set")) throw usage("/civ taxes [set <amount>]");
        BigDecimal amount = PlotTaxService.normalizeRate(CommandInput.currency(args[2], true));
        mutateFuture(player, "plot-tax-rate", () -> plotTaxes.setRate(player.getUniqueId(), amount), (audience, result) -> {
            outcome(audience, result);
            if (result.success()) Bukkit.getOnlinePlayers().stream()
                .filter(recipient -> !recipient.getUniqueId().equals(player.getUniqueId()))
                .filter(recipient -> {
                    Member citizen = cache.snapshot().member(recipient.getUniqueId());
                    return citizen != null && citizen.civilizationId() == member.civilizationId();
                }).forEach(recipient -> send(recipient, "&eYour civilization's weekly plot tax is $" + amount
                    + " per private plot. Rate changes allow at least seven days before charging. View /civ taxes."));
        });
    }

    private void stockpile(CommandSender sender, String[] args) {
        Player player = player(sender);
        Member member = requireMembership(player);
        if (args.length == 1) {
            readFuture(player, () -> progression.stockpile().balances(member.civilizationId()),
                (audience, balances) -> progression.menus().openStockpile(player, member.civilizationId(), balances));
            return;
        }
        if (!args[1].equalsIgnoreCase("history") || args.length > 3) {
            throw usage("/civ stockpile [history [page]]");
        }
        int page = args.length == 3 ? CommandInput.positiveInt(args[2], "Page") : 1;
        readFuture(player, () -> progression.stockpile().history(member.civilizationId(), page, STOCKPILE_HISTORY_SIZE),
            (audience, history) -> renderStockpileHistory(audience, history, page));
    }

    private void renderStockpileHistory(CommandSender sender, List<StockpileService.LedgerEntry> history, int page) {
        send(sender, "&6Stockpile history &7(page " + page + ")");
        if (history.isEmpty()) {
            line(sender, "&7No ledger entries exist on this page.");
            return;
        }
        for (StockpileService.LedgerEntry entry : history) {
            String sign = entry.delta() >= 0 ? "+" : "";
            line(sender, "&7" + formatShortTime(entry.createdAt()) + " &f" + sign + entry.delta() + " "
                + resourceName(entry.resource()) + " &8— &7" + title(entry.reason()));
        }
    }

    private void workOrders(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length != 1) throw usage("/civ workorders");
        progression.menus().openWorkOrders(player);
    }

    private void customItems(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length != 1) throw usage("/civ items");
        progression.menus().openCustomItems(player);
    }

    private void technologyTree(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length != 1) throw usage("/civ tech");
        progression.menus().openTechnologyTree(player);
    }

    private void research(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length == 1) {
            progression.menus().openTechnologyTree(player);
            return;
        }
        Member member = requireMembership(player);
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "tree" -> researchTree(player, args, member);
            case "start" -> researchStart(player, args, member);
            case "status" -> researchStatus(player, args, member);
            case "cancel" -> researchCancel(player, args, member);
            default -> throw usage("/civ research <tree | start | status | cancel>");
        }
    }

    private void researchTree(Player player, String[] args, Member member) {
        if (args.length != 2) throw usage("/civ research tree");
        progression.menus().openTechnologyTree(player);
    }

    private void researchStart(Player player, String[] args, Member member) {
        if (args.length < 3) throw usage("/civ research start <technology>");
        String key = CommandInput.technologyKey(CommandInput.join(args, 2));
        mutateFuture(player, "research-start", () -> progression.research().start(member.civilizationId(), player.getUniqueId(), key),
            (audience, result) -> {
                outcome(audience, new OperationResult(result.success(), result.message()));
                if (result.success()) line(audience, "&7Queue " + result.slot() + " completes "
                    + formatTime(result.completesAt(), settings.war().zone()) + ".");
            });
    }

    private void researchStatus(Player player, String[] args, Member member) {
        if (args.length != 2) throw usage("/civ research status");
        readFuture(player, () -> progression.research().status(member.civilizationId()), (audience, status) -> {
            int capacity = progression.research().queueCapacity(cache.snapshot().technologies(member.civilizationId()));
            send(audience, "&6Research status &7(" + status.size() + "/" + capacity + " queues occupied)");
            if (status.isEmpty()) line(audience, "&7No research is active.");
            status.forEach(entry -> {
                line(audience, "&eQueue " + entry.slot() + ": &f" + technologyName(entry.technologyKey()));
                line(audience, "  &7Completes " + formatTime(entry.completesAt(), settings.war().zone())
                    + " · cost " + entry.knowledgeCost() + " Knowledge, " + formatResources(entry.materialCosts()));
            });
        });
    }

    private void researchCancel(Player player, String[] args, Member member) {
        if (args.length > 3) throw usage("/civ research cancel [queue]");
        int slot;
        if (args.length == 3) slot = CommandInput.positiveInt(args[2], "Queue");
        else {
            List<ResearchEntry> active = cache.snapshot().research(member.civilizationId());
            if (active.isEmpty()) {
                send(player, "&cNo research is active.");
                return;
            }
            if (active.size() > 1) {
                send(player, "&cMore than one queue is active. Choose a queue: /civ research cancel <queue>.");
                return;
            }
            slot = active.getFirst().slot();
        }
        int queueSlot = slot;
        mutateFuture(player, "research-cancel", () -> progression.research().cancel(member.civilizationId(), player.getUniqueId(), queueSlot),
            (audience, result) -> {
                outcome(audience, new OperationResult(result.success(), result.message()));
                if (result.success()) line(audience, "&7Refunded " + result.knowledgeRefund() + " Knowledge and "
                    + formatResources(result.materialRefund()) + ".");
            });
    }

    private void treasury(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (args.length < 2) throw usage("/civ treasury <balance | deposit | withdraw | history>");
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "balance" -> treasuryBalance(player, args);
            case "deposit" -> treasuryDeposit(player, args);
            case "withdraw" -> treasuryWithdraw(player, args);
            case "history" -> treasuryHistory(player, args);
            default -> throw usage("/civ treasury <balance | deposit | withdraw | history>");
        }
    }

    private void treasuryBalance(Player player, String[] args) {
        if (args.length != 2) throw usage("/civ treasury balance");
        BigDecimal balance = treasury.balance(player.getUniqueId());
        if (balance == null) send(player, "&cYou do not belong to a civilization.");
        else send(player, "&6Treasury balance: &f" + balance.toPlainString());
    }

    private void treasuryDeposit(Player player, String[] args) {
        if (args.length != 3) throw usage("/civ treasury deposit <amount>");
        BigDecimal amount = CommandInput.currency(args[2], false);
        mutate(player, "treasury-deposit", () -> treasury.deposit(player.getUniqueId(), amount));
    }

    private void treasuryWithdraw(Player player, String[] args) {
        if (args.length != 3) throw usage("/civ treasury withdraw <amount>");
        BigDecimal amount = CommandInput.currency(args[2], false);
        mutate(player, "treasury-withdraw", () -> treasury.withdraw(player.getUniqueId(), amount));
    }

    private void treasuryHistory(Player player, String[] args) {
        if (args.length > 3) throw usage("/civ treasury history [page]");
        int page = args.length == 3 ? CommandInput.positiveInt(args[2], "Page") : 1;
        readFuture(player, () -> treasury.history(player.getUniqueId(), page), (audience, history) -> {
            send(audience, "&6Treasury history &7(page " + page + ")");
            if (history.isEmpty()) line(audience, "&7No transactions exist on this page.");
            history.forEach(entry -> {
                String sign = entry.amount().signum() >= 0 ? "+" : "";
                line(audience, "&7" + formatShortTime(entry.createdAt()) + " &f" + sign + entry.amount().toPlainString()
                    + " &8— &7" + title(entry.reason()) + " &8(&f" + entry.resulting().toPlainString() + "&8)");
            });
        });
    }

    private void war(CommandSender sender, String[] args) {
        if (args.length < 2) throw usage("/civ war <declare | status | peace | cancel>");
        String operation = args[1].toLowerCase(Locale.ROOT);
        if (operation.equals("status")) {
            warStatus(sender, args);
            return;
        }
        Player player = player(sender);
        switch (operation) {
            case "declare" -> warDeclare(player, args);
            case "peace" -> warPeace(player, args);
            case "cancel" -> warCancel(player, args);
            default -> throw usage("/civ war <declare | status | peace | cancel>");
        }
    }

    private void warDeclare(Player player, String[] args) {
        if (args.length < 3) throw usage("/civ war declare <civilization>");
        Member actor = requireMembership(player);
        if (actor.role() != Role.LEADER) {
            send(player, "&cOnly a civilization leader may declare war.");
            return;
        }
        String target = CommandInput.join(args, 2);
        Civilization defender = cache.snapshot().civilization(target);
        if (defender == null || defender.id() == actor.civilizationId()) {
            send(player, "&cNo eligible opposing civilization matches that name.");
            return;
        }
        String confirmationAction = "war-declare-confirm";
        String fingerprint = defender.id() + ":" + JsonData.costs(settings.war().declarationCost());
        Instant expires = clock.instant().plus(settings.plots().confirmationExpiry());
        confirmations.remember(player.getUniqueId(), confirmationAction, fingerprint, expires);
        send(player, "&eDeclaring war on &f" + defender.name()
            + "&e will consume one genuine War Charter and exactly &f"
            + formatResources(settings.war().declarationCost()) + "&e from the civic stockpile.");
        confirmationGui.open(player, "Declare war", List.of(
            "&7Defender: &f" + defender.name(),
            "&cConsumes one genuine War Charter.",
            "&7Stockpile cost: &f" + formatResources(settings.war().declarationCost()),
            "&cThis schedules a campaign."
        ), expires, () -> confirmWarDeclaration(player, target, fingerprint, confirmationAction),
            () -> confirmations.invalidate(player.getUniqueId(), confirmationAction));
    }

    private void confirmWarDeclaration(Player player, String target, String fingerprint, String confirmationAction) {
        if (!fingerprint.equals(confirmations.consume(player.getUniqueId(), confirmationAction))) {
            send(player, "&cThat war declaration confirmation expired or no longer matches.");
            return;
        }
        Supplier<CompletableFuture<OperationResult>> operation = () -> {
            if (!ports.warCharters().consume(player)) return CompletableFuture.completedFuture(
                OperationResult.denied("A genuine War Charter is required in your inventory."));
            CompletableFuture<OperationResult> future;
            try {
                future = wars.declare(player.getUniqueId(), target, true);
            } catch (RuntimeException failure) {
                ports.warCharters().restore(player);
                throw failure;
            }
            return future.whenComplete((result, failure) -> {
                if (failure != null || result == null || !result.success()) {
                    onMain(() -> ports.warCharters().restore(player));
                }
            });
        };
        mutateFuture(player, "war-declare", operation, (audience, result) -> {
            outcome(audience, result);
            if (!result.success()) return;
            Member member = cache.snapshot().member(player.getUniqueId());
            if (member == null) return;
            Civilization attacker = cache.snapshot().civilization(member.civilizationId());
            WarService.WarStatus status = wars.status(member.civilizationId());
            if (attacker == null || status == null || status.opponent() == null) return;
            Bukkit.broadcastMessage(Messages.color(PREFIX + "&c" + attacker.name() + " &7declared war on &c"
                + status.opponent().name() + "&7. Campaign window: &f"
                + formatTime(status.war().scheduledStart(), status.war().zoneId()) + " &7to &f"
                + formatTime(status.war().scheduledEnd(), status.war().zoneId()) + "&7."));
        });
    }

    private void warStatus(CommandSender sender, String[] args) {
        if (args.length == 2) {
            Player player = player(sender);
            Member member = requireMembership(player);
            renderWarStatus(sender, member.civilizationId());
            return;
        }
        String name = CommandInput.join(args, 2);
        Civilization civilization = cache.snapshot().civilization(name);
        if (civilization == null) {
            send(sender, "&cNo civilization matches that name.");
            return;
        }
        renderWarStatus(sender, civilization.id());
    }

    private void renderWarStatus(CommandSender sender, long civilizationId) {
        Civilization civilization = cache.snapshot().civilization(civilizationId);
        WarService.WarStatus status = warStatus(civilizationId);
        if (status == null) {
            send(sender, "&6" + (civilization == null ? "Civilization" : civilization.name()) + " &7has no unresolved campaign.");
            return;
        }
        War war = status.war();
        boolean attacker = war.attackerCivilizationId() == civilizationId;
        String opponent = status.opponent() == null ? "Unknown" : status.opponent().name();
        send(sender, "&6Campaign #" + war.id() + " &8— &f" + title(war.effectiveState(clock.instant()).name()));
        line(sender, "&7Side: &f" + (attacker ? "Attacker" : "Defender") + " &8| &7Opponent: &c" + opponent);
        line(sender, "&7Declared: &f" + formatTime(war.declaredAt(), war.zoneId()));
        line(sender, "&7War window: &f" + formatTime(war.scheduledStart(), war.zoneId()) + " &7to &f"
            + formatTime(war.scheduledEnd(), war.zoneId()));
        if (war.truceUntil() != null) line(sender, "&7Truce ends: &f" + formatTime(war.truceUntil(), war.zoneId()));
        line(sender, "&7Attacker roster (&f" + war.attackerRoster().size() + "&7): &f"
            + rosterNames(war.attackerCivilizationId(), war.attackerRoster()));
        line(sender, "&7Defender roster (&f" + war.defenderRoster().size() + "&7): &f"
            + rosterNames(war.defenderCivilizationId(), war.defenderRoster()));
        line(sender, "&7Peace: &fattacker " + yesNo(war.attackerPeace()) + "&7, defender " + yesNo(war.defenderPeace()));
        line(sender, "&7During the window: &ffighting, territory breaking, and looting.");
        line(sender, "&7War ends without a winner, land transfers, or stockpile rewards.");
    }

    private void warPeace(Player player, String[] args) {
        if (args.length != 2) throw usage("/civ war peace");
        mutate(player, "war-peace", () -> wars.peace(player.getUniqueId()));
    }

    private void warCancel(Player player, String[] args) {
        if (args.length != 2) throw usage("/civ war cancel");
        mutateFuture(player, "war-cancel", () -> wars.cancel(player.getUniqueId()), (audience, result) -> {
            if (result.success()) ports.warCharters().restore(player);
            outcome(audience, result);
        });
    }

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("civilizations.admin")) {
            send(sender, "&cYou do not have administrator permission.");
            return;
        }
        ports.admin().execute(sender, Arrays.copyOfRange(args, 1, args.length));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] arguments) {
        if (!sender.hasPermission("civilizations.use")) return List.of();
        String[] args = arguments == null ? new String[0] : arguments;
        if (args.length == 0) return List.of();
        if (args.length == 1) {
            List<String> roots = new ArrayList<>(ROOT_COMMANDS);
            if (sender.hasPermission("civilizations.admin")) roots.add("admin");
            return CommandInput.matching(roots, args[0]);
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (root.equals("admin")) {
            if (!sender.hasPermission("civilizations.admin")) return List.of();
            return ports.admin().complete(sender, Arrays.copyOfRange(args, 1, args.length));
        }
        Player player = sender instanceof Player value ? value : null;
        return switch (root) {
            case "help" -> args.length == 2 ? CommandInput.matching(
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "membership", "territory", "plots",
                    "resources", "research", "treasury", "taxes", "war"), args[1]) : List.of();
            case "info", "accept", "deny" -> civilizationCompletion(args, 1);
            case "invite" -> args.length == 2 ? CommandInput.matching(ports.players().knownNames(), args[1]) : List.of();
            case "kick", "transfer" -> memberCompletion(player, args, 1, null);
            case "advisor" -> advisorCompletion(player, args);
            case "plot" -> plotCompletion(player, args);
            case "deposit" -> args.length == 2 ? CommandInput.matching(List.of("hand", "all"), args[1]) : List.of();
            case "taxes" -> args.length == 2 ? CommandInput.matching(List.of("set"), args[1]) : List.of();
            case "stockpile" -> args.length == 2 ? CommandInput.matching(List.of("history"), args[1]) : List.of();
            case "teleport" -> args.length == 2
                ? CommandInput.matching(List.of("overworld", "nether", "end"), args[1]) : List.of();
            case "research" -> researchCompletion(player, args);
            case "treasury" -> treasuryCompletion(args);
            case "war" -> warCompletion(player, args);
            default -> List.of();
        };
    }

    private List<String> civilizationCompletion(String[] args, int start) {
        if (args.length != start + 1) return List.of();
        List<String> values = new ArrayList<>();
        cache.snapshot().civilizations().values().forEach(civilization -> values.add(civilization.name()));
        return CommandInput.matching(values, args[start]);
    }

    private List<String> memberCompletion(Player player, String[] args, int index, Role role) {
        if (player == null || args.length != index + 1) return List.of();
        Member actor = cache.snapshot().member(player.getUniqueId());
        if (actor == null) return List.of();
        List<String> names = cache.snapshot().members(actor.civilizationId()).stream()
            .filter(member -> !member.playerId().equals(player.getUniqueId()))
            .filter(member -> role == null || member.role() == role)
            .map(Member::lastKnownName).toList();
        return CommandInput.matching(names, args[index]);
    }

    private List<String> advisorCompletion(Player player, String[] args) {
        if (args.length == 2) return CommandInput.matching(List.of("add", "remove"), args[1]);
        if (args.length == 3) {
            Role role = args[1].equalsIgnoreCase("add") ? Role.CITIZEN
                : args[1].equalsIgnoreCase("remove") ? Role.ADVISOR : null;
            return role == null ? List.of() : memberCompletion(player, args, 2, role);
        }
        return List.of();
    }

    private List<String> plotCompletion(Player player, String[] args) {
        if (args.length == 2) return CommandInput.matching(
            List.of("type", "defaultprice", "list", "unlist", "buy", "sell", "surrender", "trust", "untrust", "label", "greeting", "flags"), args[1]);
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3) {
            return switch (sub) {
                case "type" -> CommandInput.matching(List.of("civic", "common"), args[2]);
                case "trust", "untrust" -> memberCompletion(player, args, 2, null);
                case "flags" -> CommandInput.matching(PLOT_FLAGS, args[2]);
                default -> List.of();
            };
        }
        if (args.length == 4 && sub.equals("flags")) return CommandInput.matching(List.of("on", "off"), args[3]);
        return List.of();
    }

    private List<String> researchCompletion(Player player, String[] args) {
        if (args.length == 2) return CommandInput.matching(List.of("tree", "start", "status", "cancel"), args[1]);
        if (args.length == 3 && args[1].equalsIgnoreCase("start")) {
            Set<String> unlocked = Set.of();
            if (player != null) {
                Member member = cache.snapshot().member(player.getUniqueId());
                if (member != null) unlocked = cache.snapshot().technologies(member.civilizationId());
            }
            Set<String> finalUnlocked = unlocked;
            List<String> keys = catalogs.technologies().technologies().keySet().stream()
                .filter(key -> !finalUnlocked.contains(key)).toList();
            return CommandInput.matching(keys, args[2]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("cancel") && player != null) {
            Member member = cache.snapshot().member(player.getUniqueId());
            if (member == null) return List.of();
            return CommandInput.matching(cache.snapshot().research(member.civilizationId()).stream()
                .map(entry -> Integer.toString(entry.slot())).toList(), args[2]);
        }
        return List.of();
    }

    private List<String> treasuryCompletion(String[] args) {
        return args.length == 2
            ? CommandInput.matching(List.of("balance", "deposit", "withdraw", "history"), args[1]) : List.of();
    }

    private List<String> warCompletion(Player player, String[] args) {
        if (args.length == 2) return CommandInput.matching(List.of("declare", "status", "peace", "cancel"), args[1]);
        if (args.length == 3 && (args[1].equalsIgnoreCase("declare") || args[1].equalsIgnoreCase("status"))) {
            long own = -1;
            if (player != null) {
                Member member = cache.snapshot().member(player.getUniqueId());
                if (member != null) own = member.civilizationId();
            }
            long finalOwn = own;
            List<String> values = cache.snapshot().civilizations().values().stream()
                .filter(civilization -> !args[1].equalsIgnoreCase("declare") || civilization.id() != finalOwn)
                .map(Civilization::name).toList();
            return CommandInput.matching(values, args[2]);
        }
        return List.of();
    }

    private void mutate(Player player, String action, Supplier<CompletableFuture<OperationResult>> operation) {
        mutateFuture(player, action, operation, CivCommand::outcome);
    }

    private <T> void mutateFuture(Player player, String action, Supplier<CompletableFuture<T>> operation,
                                  BiConsumer<CommandSender, T> completion) {
        UUID playerId = player.getUniqueId();
        if (mutations.putIfAbsent(playerId, action) != null) {
            send(player, "&cYou already have a civilization operation processing. Please wait for it to finish.");
            return;
        }
        send(player, "&7Processing…");
        CompletableFuture<T> future;
        try {
            future = Objects.requireNonNull(operation.get(), "operation returned null");
        } catch (Throwable failure) {
            mutations.remove(playerId, action);
            send(player, "&cThe operation could not start: " + rootMessage(failure));
            return;
        }
        future.whenComplete((value, failure) -> onMain(() -> {
            mutations.remove(playerId, action);
            if (failure != null) {
                send(player, "&cThe operation failed safely: " + rootMessage(failure));
                return;
            }
            completion.accept(player, value);
        }));
    }

    private <T> void readFuture(CommandSender sender, Supplier<CompletableFuture<T>> operation,
                                BiConsumer<CommandSender, T> completion) {
        CompletableFuture<T> future;
        try {
            future = Objects.requireNonNull(operation.get(), "operation returned null");
        } catch (Throwable failure) {
            send(sender, "&cThe request could not start: " + rootMessage(failure));
            return;
        }
        future.whenComplete((value, failure) -> onMain(() -> {
            if (failure != null) send(sender, "&cThe request failed: " + rootMessage(failure));
            else completion.accept(sender, value);
        }));
    }

    private void onMain(Runnable task) {
        try {
            mainThread.accept(task);
        } catch (RuntimeException ignored) {
            // The server is most likely already shutting down; do not run Bukkit effects off-thread.
        }
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) return player;
        throw new IllegalArgumentException("That command can only be used by a player.");
    }

    private Member requireMembership(Player player) {
        Member member = cache.snapshot().member(player.getUniqueId());
        if (member == null) throw new IllegalArgumentException("You do not belong to a civilization.");
        return member;
    }

    private Optional<Member> memberByName(long civilizationId, String name) {
        if (name == null) return Optional.empty();
        return cache.snapshot().members(civilizationId).stream()
            .filter(member -> member.lastKnownName() != null && member.lastKnownName().equalsIgnoreCase(name)).findFirst();
    }

    private static ChunkKey chunk(Player player) {
        return chunk(player.getLocation());
    }

    private static ChunkKey chunk(Location location) {
        World world = Objects.requireNonNull(location.getWorld(), "player world");
        return new ChunkKey(world.getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    /** WarService indexes unresolved campaigns; this fallback keeps an active truce visible to commands. */
    private WarService.WarStatus warStatus(long civilizationId) {
        WarService.WarStatus current = wars.status(civilizationId);
        if (current != null) return current;
        StateSnapshot state = cache.snapshot();
        War truce = state.wars().values().stream()
            .filter(war -> war.attackerCivilizationId() == civilizationId || war.defenderCivilizationId() == civilizationId)
            .filter(war -> war.state() == io.github.empireage.civilizations.domain.WarState.TRUCE)
            .filter(war -> war.truceUntil() == null || war.truceUntil().isAfter(clock.instant()))
            .max(Comparator.comparing(War::declaredAt)).orElse(null);
        if (truce == null) return null;
        long opponentId = truce.opponent(civilizationId);
        return new WarService.WarStatus(truce, state.civilization(opponentId));
    }

    private String rosterNames(long civilizationId, Set<UUID> roster) {
        if (roster.isEmpty()) return "Not snapshotted yet";
        Map<UUID, String> known = new LinkedHashMap<>();
        cache.snapshot().members(civilizationId).forEach(member -> known.put(member.playerId(), member.lastKnownName()));
        return roster.stream().map(id -> known.getOrDefault(id, id.toString())).sorted(String.CASE_INSENSITIVE_ORDER)
            .collect(java.util.stream.Collectors.joining(", "));
    }

    private String technologyName(String key) {
        TechnologyDefinition definition = catalogs.technologies().get(key);
        return definition == null ? key : definition.name();
    }

    private String formatResources(Map<ResourceKey, Long> resources) {
        return ResourceText.cost(catalogs.resources(), resources);
    }

    private String resourceName(ResourceKey key) {
        return ResourceText.name(catalogs.resources(), key);
    }

    private String formatTime(Instant instant, ZoneId zone) {
        if (instant == null) return "not scheduled";
        return DATE_TIME.withZone(zone).format(instant) + " (" + TimeUtil.relative(instant, clock.instant()) + ")";
    }

    private String formatShortTime(Instant instant) {
        return DATE_TIME.withZone(settings.war().zone()).format(instant);
    }

    private static String mapColor(char marker) {
        return switch (marker) {
            case '@' -> "&f";
            case 'K' -> "&6";
            case 'C' -> "&e";
            case 'M' -> "&a";
            case '$' -> "&b";
            case 'P' -> "&d";
            case 'X' -> "&c";
            case '!' -> "&4";
            default -> "&7";
        };
    }

    private static String yesNo(boolean value) {
        return value ? "&ayes" : "&cno";
    }

    private static String kickFingerprint(Member target, List<Claim> plots) {
        String property = plots.stream().sorted(Comparator.comparingLong(Claim::id))
            .map(claim -> claim.id() + ":" + claim.rowVersion())
            .collect(java.util.stream.Collectors.joining(","));
        return target.playerId() + ":" + target.joinedAt() + ":" + property;
    }

    private static String title(Role role) {
        return title(role.name());
    }

    private static String title(Enum<?> value) {
        return title(value.name());
    }

    private static String title(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder result = new StringBuilder(normalized.length());
        boolean capitalize = true;
        for (char character : normalized.toCharArray()) {
            if (capitalize && Character.isLetter(character)) {
                result.append(Character.toUpperCase(character));
                capitalize = false;
            } else {
                result.append(character);
                if (character == ' ') capitalize = true;
            }
        }
        return result.toString();
    }

    private static IllegalArgumentException usage(String usage) {
        return new IllegalArgumentException("Usage: " + usage);
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
            || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static void outcome(CommandSender sender, OperationResult result) {
        send(sender, (result.success() ? "&a" : "&c") + result.message());
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(Messages.color(PREFIX + message));
    }

    private static void line(CommandSender sender, String message) {
        sender.sendMessage(Messages.color(message));
    }
}
