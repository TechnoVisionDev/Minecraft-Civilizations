package io.github.empireage.civilizations.service.admin;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.command.CommandPorts;
import io.github.empireage.civilizations.command.ConfirmationGui;
import io.github.empireage.civilizations.config.ResourceCatalog;
import io.github.empireage.civilizations.config.TechnologyCatalog;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.Civilization;
import io.github.empireage.civilizations.domain.ResourceKey;
import io.github.empireage.civilizations.domain.Role;
import io.github.empireage.civilizations.domain.WarState;
import io.github.empireage.civilizations.service.admin.AdminModels.AuditEntry;
import io.github.empireage.civilizations.service.admin.AdminModels.AuditFilter;
import io.github.empireage.civilizations.service.admin.AdminModels.Inspection;
import io.github.empireage.civilizations.service.admin.AdminModels.Result;
import io.github.empireage.civilizations.service.admin.AdminModels.SchemaReport;
import io.github.empireage.civilizations.service.admin.InvariantModels.Report;
import io.github.empireage.civilizations.service.admin.InvariantModels.Violation;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Complete implementation of the `/civ admin ...` command port. */
public final class AdminCommandAdapter implements CommandPorts.AdminCommandPort {
    private static final String GENERAL_PERMISSION = "civilizations.admin";
    private static final String DATA_PERMISSION = "civilizations.admin.data";
    private static final String WAR_PERMISSION = "civilizations.admin.war";
    private static final String RELOAD_PERMISSION = "civilizations.admin.reload";

    private final JavaPlugin plugin;
    private final AdminService admin;
    private final InvariantChecker invariants;
    private final StateCache cache;
    private final ResourceCatalog resources;
    private final TechnologyCatalog technologies;
    private final CommandPorts.PlayerDirectoryPort players;
    private final ReloadHandler reload;
    private final ConfirmationGui confirmationGui;
    private final Duration confirmationExpiry;

    public AdminCommandAdapter(JavaPlugin plugin, AdminService admin, InvariantChecker invariants,
                               StateCache cache, ResourceCatalog resources, TechnologyCatalog technologies,
                               CommandPorts.PlayerDirectoryPort players, ReloadHandler reload,
                               ConfirmationGui confirmationGui, Duration confirmationExpiry) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.admin = Objects.requireNonNull(admin, "admin");
        this.invariants = Objects.requireNonNull(invariants, "invariants");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.technologies = Objects.requireNonNull(technologies, "technologies");
        this.players = Objects.requireNonNull(players, "players");
        this.reload = reload == null ? () -> Result.denied("Runtime reload is not configured.") : reload;
        this.confirmationGui = Objects.requireNonNull(confirmationGui, "confirmationGui");
        this.confirmationExpiry = Objects.requireNonNull(confirmationExpiry, "confirmationExpiry");
    }

    @Override
    public boolean execute(CommandSender sender, String[] rawArguments) {
        Objects.requireNonNull(sender, "sender");
        String[] arguments = rawArguments == null ? new String[0] : rawArguments;
        if (!sender.hasPermission(GENERAL_PERMISSION)) {
            error(sender, "You do not have administrator permission.");
            return true;
        }
        try {
            if (arguments.length == 0 || arguments[0].equalsIgnoreCase("help")) {
                help(sender);
                return true;
            }
            switch (arguments[0].toLowerCase(Locale.ROOT)) {
                case "reload" -> reload(sender, arguments);
                case "inspect" -> inspect(sender, arguments);
                case "setrole" -> setRole(sender, arguments);
                case "forceclaim" -> forceClaim(sender, arguments);
                case "forceunclaim" -> forceUnclaim(sender, arguments);
                case "grantresource" -> grantResource(sender, arguments);
                case "grantknowledge" -> grantKnowledge(sender, arguments);
                case "research" -> research(sender, arguments);
                case "war" -> war(sender, arguments);
                case "audit" -> audit(sender, arguments);
                case "migrate", "schema" -> schema(sender, arguments);
                case "invariants", "check" -> invariants(sender, arguments);
                default -> throw usage("/civ admin help");
            }
        } catch (IllegalArgumentException invalid) {
            error(sender, invalid.getMessage());
        } catch (RuntimeException unexpected) {
            error(sender, "Administrator command failed before it could start: " + rootMessage(unexpected));
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Admin command dispatch failed", unexpected);
        }
        return true;
    }

    @Override
    public List<String> complete(CommandSender sender, String[] rawArguments) {
        if (!sender.hasPermission(GENERAL_PERMISSION)) return List.of();
        String[] arguments = rawArguments == null ? new String[0] : rawArguments;
        if (arguments.length == 0) return availableRoots(sender);
        if (arguments.length == 1) return matching(availableRoots(sender), arguments[0]);
        String root = arguments[0].toLowerCase(Locale.ROOT);
        if (arguments.length == 2) {
            return switch (root) {
                case "inspect" -> matching(List.of("civ", "claim", "player", "war"), arguments[1]);
                case "setrole" -> sender.hasPermission(DATA_PERMISSION) ? matching(players.knownNames(), arguments[1]) : List.of();
                case "forceclaim", "grantresource", "grantknowledge" -> sender.hasPermission(DATA_PERMISSION)
                    ? matching(civilizationIdentities(), arguments[1]) : List.of();
                case "forceunclaim" -> sender.hasPermission(DATA_PERMISSION) && !(sender instanceof Player)
                    ? matching(List.of("confirm"), arguments[1]) : List.of();
                case "research" -> sender.hasPermission(DATA_PERMISSION) ? matching(List.of("unlock", "lock"), arguments[1]) : List.of();
                case "war" -> sender.hasPermission(WAR_PERMISSION) ? matching(List.of("cancel", "resolve", "setstate"), arguments[1]) : List.of();
                case "audit" -> matching(List.of("page=1", "size=20", "action=admin.", "civ=", "actor=", "since=", "before="), arguments[1]);
                default -> List.of();
            };
        }
        if (root.equals("inspect") && arguments.length == 3) {
            return switch (arguments[1].toLowerCase(Locale.ROOT)) {
                case "civ" -> matching(civilizationIdentities(), arguments[2]);
                case "player" -> matching(players.knownNames(), arguments[2]);
                default -> List.of();
            };
        }
        if (root.equals("setrole") && arguments.length == 3 && sender.hasPermission(DATA_PERMISSION)) {
            return matching(List.of("leader", "advisor", "citizen"), arguments[2]);
        }
        if (root.equals("research") && sender.hasPermission(DATA_PERMISSION)) {
            if (arguments.length == 3) return matching(civilizationIdentities(), arguments[2]);
            if (arguments.length == 4) return matching(technologies.technologies().keySet(), arguments[3]);
            if (arguments.length == 5 && !(sender instanceof Player)) return matching(List.of("confirm"), arguments[4]);
        }
        if (root.equals("war") && sender.hasPermission(WAR_PERMISSION)) {
            if (arguments.length == 4 && arguments[1].equalsIgnoreCase("setstate")) {
                return matching(Arrays.stream(WarState.values()).map(value -> value.name().toLowerCase(Locale.ROOT)).toList(), arguments[3]);
            }
            if (!(sender instanceof Player) && (arguments.length == 4 || arguments.length == 5)) {
                return matching(List.of("confirm"), arguments[arguments.length - 1]);
            }
        }
        if (!(sender instanceof Player) && (root.equals("forceclaim") || root.equals("forceunclaim"))
            && sender.hasPermission(DATA_PERMISSION)) {
            return matching(List.of("confirm"), arguments[arguments.length - 1]);
        }
        return List.of();
    }

    private void help(CommandSender sender) {
        line(sender, "&6Civilizations administration");
        line(sender, "&e/civ admin inspect <civ | claim | player | war> [target]");
        if (sender.hasPermission(DATA_PERMISSION)) {
            line(sender, "&e/civ admin setrole <player> <leader | advisor | citizen>");
            line(sender, "&e/civ admin forceclaim <civilization> &8(in-game warning GUI)");
            line(sender, "&e/civ admin forceunclaim &8(in-game warning GUI)");
            line(sender, "&e/civ admin grantresource <civ> <key> <tier> <signed-amount>");
            line(sender, "&e/civ admin grantknowledge <civ> <signed-amount>");
            line(sender, "&e/civ admin research <unlock | lock> <civ> <technology> &8(GUI when warnings exist)");
        }
        if (sender.hasPermission(WAR_PERMISSION)) {
            line(sender, "&e/civ admin war <cancel | resolve> <id> [reason] &8(GUI when warnings exist)");
            line(sender, "&e/civ admin war setstate <id> <state> &8(GUI when warnings exist)");
        }
        if (sender.hasPermission(RELOAD_PERMISSION)) line(sender, "&e/civ admin reload");
        line(sender, "&e/civ admin audit [civ=ID] [actor=UUID] [action=PREFIX] [target=TYPE:ID] [page=N] [size=N]");
        line(sender, "&e/civ admin migrate &8(schema status only)");
        line(sender, "&e/civ admin invariants");
    }

    private void reload(CommandSender sender, String[] arguments) {
        requirePermission(sender, RELOAD_PERMISSION);
        if (arguments.length != 1) throw usage("/civ admin reload");
        Result result;
        try {
            result = Objects.requireNonNull(reload.reload(), "reload handler result");
        } catch (Exception failure) {
            result = Result.denied("Configuration reload failed: " + rootMessage(failure));
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Configuration reload failed", failure);
        }
        Result visible = result;
        line(sender, "&7Writing the reload audit entry...");
        admin.auditReload(actor(sender), result.success(), result.message()).whenComplete((audited, failure) -> onMain(() -> {
            if (failure != null) {
                render(sender, visible);
                error(sender, "The reload audit entry failed: " + rootMessage(failure));
            } else render(sender, audited);
        }));
    }

    private void inspect(CommandSender sender, String[] arguments) {
        if (arguments.length < 2) throw usage("/civ admin inspect <civ | claim | player | war> [target]");
        switch (arguments[1].toLowerCase(Locale.ROOT)) {
            case "civ" -> {
                if (arguments.length < 3) throw usage("/civ admin inspect civ <name | id>");
                submit(sender, admin.inspectCivilization(join(arguments, 2, arguments.length)),
                    inspection -> renderInspection(sender, inspection));
            }
            case "claim" -> {
                ChunkKey key;
                if (arguments.length == 2) key = currentChunk(sender);
                else if (arguments.length == 5) key = new ChunkKey(parseUuid(arguments[2], "world UUID"),
                    integer(arguments[3], "chunk X"), integer(arguments[4], "chunk Z"));
                else throw usage("/civ admin inspect claim [world-uuid chunk-x chunk-z]");
                submit(sender, admin.inspectClaim(key), inspection -> renderInspection(sender, inspection));
            }
            case "player" -> {
                if (arguments.length != 3) throw usage("/civ admin inspect player <player | uuid>");
                UUID player = resolvePlayer(arguments[2]).id();
                submit(sender, admin.inspectPlayer(player), inspection -> renderInspection(sender, inspection));
            }
            case "war" -> {
                if (arguments.length != 3) throw usage("/civ admin inspect war <id>");
                submit(sender, admin.inspectWar(positiveLong(arguments[2], "war ID")),
                    inspection -> renderInspection(sender, inspection));
            }
            default -> throw usage("/civ admin inspect <civ | claim | player | war> [target]");
        }
    }

    private void setRole(CommandSender sender, String[] arguments) {
        requirePermission(sender, DATA_PERMISSION);
        if (arguments.length != 3) throw usage("/civ admin setrole <player> <role>");
        CommandPorts.PlayerIdentity player = resolvePlayer(arguments[1]);
        Role role;
        try { role = Role.valueOf(arguments[2].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Role must be leader, advisor, or citizen."); }
        submitResult(sender, admin.setRole(actor(sender), player.id(), role));
    }

    private void forceClaim(CommandSender sender, String[] arguments) {
        requirePermission(sender, DATA_PERMISSION);
        Player player = requirePlayer(sender, "Force-claim must be run by a player standing in the target chunk.");
        if (arguments.length < 2) throw usage("/civ admin forceclaim <civilization>");
        String civilization = join(arguments, 1, arguments.length);
        ChunkKey target = currentChunk(player);
        CompletableFuture<Result> operation = admin.forceClaim(player.getUniqueId(), civilization, target,
            player.getWorld().getName(), false);
        submitConfirmable(player, operation, "Confirm force-claim",
            () -> submitResult(player, admin.forceClaim(player.getUniqueId(), civilization, target,
                player.getWorld().getName(), true)));
    }

    private void forceUnclaim(CommandSender sender, String[] arguments) {
        requirePermission(sender, DATA_PERMISSION);
        Player player = requirePlayer(sender, "Force-unclaim must be run by a player standing in the target chunk.");
        if (arguments.length != 1) throw usage("/civ admin forceunclaim");
        ChunkKey target = currentChunk(player);
        CompletableFuture<Result> operation = admin.forceUnclaim(player.getUniqueId(), target, false);
        submitConfirmable(player, operation, "Confirm force-unclaim",
            () -> submitResult(player, admin.forceUnclaim(player.getUniqueId(), target, true)));
    }

    private void grantResource(CommandSender sender, String[] arguments) {
        requirePermission(sender, DATA_PERMISSION);
        if (arguments.length < 5) throw usage("/civ admin grantresource <civ> <key> <tier> <signed-amount>");
        long amount = signedLong(arguments[arguments.length - 1], "amount");
        int tier = integer(arguments[arguments.length - 2], "tier");
        String family = arguments[arguments.length - 3].toLowerCase(Locale.ROOT);
        String civilization = join(arguments, 1, arguments.length - 3);
        ResourceKey key = new ResourceKey(family, tier);
        resources.require(key);
        submitResult(sender, admin.grantResource(actor(sender), civilization, key, amount));
    }

    private void grantKnowledge(CommandSender sender, String[] arguments) {
        requirePermission(sender, DATA_PERMISSION);
        if (arguments.length < 3) throw usage("/civ admin grantknowledge <civ> <signed-amount>");
        long amount = signedLong(arguments[arguments.length - 1], "amount");
        String civilization = join(arguments, 1, arguments.length - 1);
        submitResult(sender, admin.grantKnowledge(actor(sender), civilization, amount));
    }

    private void research(CommandSender sender, String[] arguments) {
        requirePermission(sender, DATA_PERMISSION);
        if (arguments.length < 4) {
            throw usage("/civ admin research <unlock | lock> <civ> <technology> [confirm]");
        }
        AdminService.ResearchRepair repair = switch (arguments[1].toLowerCase(Locale.ROOT)) {
            case "unlock" -> AdminService.ResearchRepair.UNLOCK;
            case "lock" -> AdminService.ResearchRepair.LOCK;
            default -> throw new IllegalArgumentException("Research repair must be unlock or lock.");
        };
        boolean confirm = arguments[arguments.length - 1].equalsIgnoreCase("confirm");
        if (confirm && sender instanceof Player) {
            throw new IllegalArgumentException("Run the research repair without 'confirm' and use the confirmation menu.");
        }
        int technologyIndex = confirm ? arguments.length - 2 : arguments.length - 1;
        if (technologyIndex < 3) throw usage("/civ admin research <unlock | lock> <civ> <technology> [confirm]");
        String technology = arguments[technologyIndex].toLowerCase(Locale.ROOT).replace('-', '_');
        String civilization = join(arguments, 2, technologyIndex);
        UUID actor = actor(sender);
        CompletableFuture<Result> operation = admin.repairResearch(actor, repair, civilization, technology, confirm);
        if (!confirm && sender instanceof Player player) submitConfirmable(player, operation, "Confirm research repair",
            () -> submitResult(player, admin.repairResearch(actor, repair, civilization, technology, true)));
        else submitResult(sender, operation);
    }

    private void war(CommandSender sender, String[] arguments) {
        requirePermission(sender, WAR_PERMISSION);
        if (arguments.length < 3) throw usage("/civ admin war <cancel | resolve | setstate> <id> ...");
        String action = arguments[1].toLowerCase(Locale.ROOT);
        long warId = positiveLong(arguments[2], "war ID");
        if (action.equals("setstate")) {
            if (arguments.length < 4 || arguments.length > 5) throw usage("/civ admin war setstate <id> <state> [confirm]");
            WarState state;
            try { state = WarState.valueOf(arguments[3].toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Unknown war state: " + arguments[3]); }
            boolean confirm = arguments.length == 5 && arguments[4].equalsIgnoreCase("confirm");
            if (arguments.length == 5 && !confirm) throw usage("/civ admin war setstate <id> <state> [confirm]");
            if (confirm && sender instanceof Player) {
                throw new IllegalArgumentException("Run the campaign repair without 'confirm' and use the confirmation menu.");
            }
            UUID actor = actor(sender);
            CompletableFuture<Result> operation = admin.setWarState(actor, warId, state, confirm);
            if (!confirm && sender instanceof Player player) submitConfirmable(player, operation, "Confirm campaign repair",
                () -> submitResult(player, admin.setWarState(actor, warId, state, true)));
            else submitResult(sender, operation);
            return;
        }
        boolean confirm = arguments.length >= 4 && arguments[3].equalsIgnoreCase("confirm");
        if (confirm && sender instanceof Player) {
            throw new IllegalArgumentException("Run the campaign repair without 'confirm' and use the confirmation menu.");
        }
        int reasonStart = confirm ? 4 : 3;
        String reason = reasonStart >= arguments.length ? "ADMIN_COMMAND" : join(arguments, reasonStart, arguments.length);
        UUID actor = actor(sender);
        switch (action) {
            case "cancel" -> {
                CompletableFuture<Result> operation = admin.cancelWar(actor, warId, reason, confirm);
                if (!confirm && sender instanceof Player player) submitConfirmable(player, operation, "Confirm campaign cancellation",
                    () -> submitResult(player, admin.cancelWar(actor, warId, reason, true)));
                else submitResult(sender, operation);
            }
            case "resolve" -> {
                CompletableFuture<Result> operation = admin.resolveWar(actor, warId, reason, confirm);
                if (!confirm && sender instanceof Player player) submitConfirmable(player, operation, "Confirm campaign resolution",
                    () -> submitResult(player, admin.resolveWar(actor, warId, reason, true)));
                else submitResult(sender, operation);
            }
            default -> throw usage("/civ admin war <cancel | resolve | setstate> <id> ...");
        }
    }

    private void audit(CommandSender sender, String[] arguments) {
        AuditFilter filter = parseAuditFilter(Arrays.copyOfRange(arguments, 1, arguments.length));
        submit(sender, admin.queryAudit(filter), entries -> renderAudit(sender, entries, filter));
    }

    private void schema(CommandSender sender, String[] arguments) {
        if (arguments.length != 1) throw usage("/civ admin migrate");
        submit(sender, admin.schemaReport(), report -> renderSchema(sender, report));
    }

    private void invariants(CommandSender sender, String[] arguments) {
        if (arguments.length != 1) throw usage("/civ admin invariants");
        submit(sender, invariants.check(), report -> renderInvariants(sender, report));
    }

    private AuditFilter parseAuditFilter(String[] tokens) {
        Long civilizationId = null;
        UUID actor = null;
        String action = null;
        String targetType = null;
        String targetId = null;
        Instant since = null;
        Instant before = null;
        int page = 1;
        int size = 20;
        for (String token : tokens) {
            int separator = token.indexOf('=');
            if (separator < 1) throw new IllegalArgumentException("Audit filters use key=value syntax: " + token);
            String key = token.substring(0, separator).toLowerCase(Locale.ROOT);
            String value = token.substring(separator + 1);
            switch (key) {
                case "civ", "civilization" -> civilizationId = resolveCivilizationId(value);
                case "actor" -> actor = resolvePlayer(value).id();
                case "action" -> action = value;
                case "target" -> {
                    int colon = value.indexOf(':');
                    if (colon < 1 || colon == value.length() - 1) throw new IllegalArgumentException("target must be TYPE:ID.");
                    targetType = value.substring(0, colon); targetId = value.substring(colon + 1);
                }
                case "since" -> since = instant(value, "since");
                case "before" -> before = instant(value, "before");
                case "page" -> page = positiveInteger(value, "page");
                case "size" -> size = positiveInteger(value, "size");
                default -> throw new IllegalArgumentException("Unknown audit filter: " + key);
            }
        }
        return new AuditFilter(civilizationId, actor, action, targetType, targetId, since, before, page, size);
    }

    private void renderInspection(CommandSender sender, Inspection inspection) {
        line(sender, "&6" + title(inspection.subjectType()) + " inspection: &f" + inspection.subjectId());
        inspection.warnings().forEach(warning -> line(sender, "&eWarning: " + warning));
        renderMap(sender, "Cached", inspection.cached());
        renderMap(sender, "Persisted", inspection.persisted());
    }

    private void renderMap(CommandSender sender, String heading, Map<String, String> values) {
        line(sender, "&e" + heading + ":");
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
            line(sender, "  &7" + entry.getKey() + ": &f" + entry.getValue()));
    }

    private void renderAudit(CommandSender sender, List<AuditEntry> entries, AuditFilter filter) {
        line(sender, "&6Audit page " + filter.page() + " &7(" + entries.size() + " entries)");
        for (AuditEntry entry : entries) {
            line(sender, "&e#" + entry.id() + " &f" + entry.action() + " &7" + entry.targetType() + ":"
                + entry.targetId() + " civ=" + entry.civilizationId() + " actor=" + entry.actorId()
                + " at=" + entry.createdAt());
            if (entry.metadataJson() != null && !entry.metadataJson().equals("{}")) line(sender, "  &8" + entry.metadataJson());
        }
    }

    private void renderSchema(CommandSender sender, SchemaReport report) {
        line(sender, report.upToDate() ? "&aSchema is up to date." : "&eSchema needs attention.");
        line(sender, "&7Database: &f" + report.databaseProduct() + " &7schema=&f" + report.schema());
        line(sender, "&7Version: &f" + report.currentVersion() + "/" + report.expectedVersion());
        report.migrations().forEach(migration -> line(sender, "  &7V" + migration.version() + " " + migration.description()
            + ": &f" + migration.status()));
        if (!report.missingTables().isEmpty()) line(sender, "&cMissing tables: " + String.join(", ", report.missingTables()));
        report.warnings().forEach(warning -> line(sender, "&eWarning: " + warning));
    }

    private void renderInvariants(CommandSender sender, Report report) {
        line(sender, report.healthy() ? "&aInvariant scan passed." : "&cInvariant scan found "
            + report.violations().size() + " issue(s), including " + report.errorCount() + " error(s).");
        for (Violation violation : report.violations()) line(sender, (violation.severity() == InvariantModels.Severity.ERROR ? "&c" : "&e")
            + violation.kind() + " &f" + violation.identity() + "&7: " + violation.message() + " " + violation.context());
    }

    private void submitResult(CommandSender sender, CompletableFuture<Result> future) {
        submit(sender, future, result -> render(sender, result));
    }

    private void submitConfirmable(Player player, CompletableFuture<Result> preview, String title, Runnable confirmed) {
        line(player, "&7Checking the operation...");
        preview.whenComplete((result, failure) -> onMain(() -> {
            if (failure != null) {
                error(player, "Operation failed: " + rootMessage(failure));
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Administrator operation failed", failure);
                return;
            }
            render(player, result);
            if (result.success() || result.warnings().isEmpty()) return;
            List<String> details = new ArrayList<>();
            details.add("&cHigh-risk administrator action");
            result.warnings().stream().limit(5).forEach(warning -> details.add("&e" + warning));
            confirmationGui.open(player, title, details, Instant.now().plus(confirmationExpiry), confirmed, () -> {});
        }));
    }

    private <T> void submit(CommandSender sender, CompletableFuture<T> future, Consumer<T> renderer) {
        line(sender, "&7Processing asynchronously...");
        future.whenComplete((value, failure) -> onMain(() -> {
            if (failure != null) {
                error(sender, "Operation failed: " + rootMessage(failure));
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Administrator operation failed", failure);
            } else renderer.accept(value);
        }));
    }

    private void render(CommandSender sender, Result result) {
        line(sender, (result.success() ? "&a" : "&c") + result.message());
        result.warnings().forEach(warning -> line(sender, "&eWarning: " + warning));
        if (!result.success() && !result.warnings().isEmpty()) {
            line(sender, sender instanceof Player
                ? "&7Review every warning, then choose the green or red block in the confirmation menu."
                : "&7Repeat the command with &fconfirm&7 after reviewing every warning.");
        }
    }

    private void onMain(Runnable work) {
        if (!plugin.isEnabled()) return;
        Runnable guarded = () -> {
            if (plugin.isEnabled()) work.run();
        };
        if (Bukkit.isPrimaryThread()) guarded.run();
        else {
            try {
                plugin.getServer().getScheduler().runTask(plugin, guarded);
            } catch (RuntimeException ignored) {
                // An async completion may race plugin shutdown.
            }
        }
    }

    private List<String> availableRoots(CommandSender sender) {
        List<String> roots = new ArrayList<>(List.of("help", "inspect", "audit", "migrate", "invariants"));
        if (sender.hasPermission(RELOAD_PERMISSION)) roots.add("reload");
        if (sender.hasPermission(DATA_PERMISSION)) roots.addAll(List.of("setrole", "forceclaim", "forceunclaim",
            "grantresource", "grantknowledge", "research"));
        if (sender.hasPermission(WAR_PERMISSION)) roots.add("war");
        return List.copyOf(roots);
    }

    private Collection<String> civilizationIdentities() {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        cache.snapshot().civilizations().values().stream().sorted(Comparator.comparing(Civilization::name,
            String.CASE_INSENSITIVE_ORDER)).forEach(civilization -> {
                values.add(civilization.name()); values.add(Long.toString(civilization.id()));
            });
        return values;
    }

    private long resolveCivilizationId(String value) {
        try {
            long id = Long.parseLong(value);
            if (cache.snapshot().civilization(id) != null) return id;
        } catch (NumberFormatException ignored) {}
        Civilization civilization = cache.snapshot().civilization(value);
        if (civilization == null) throw new IllegalArgumentException("No cached civilization matches " + value + ".");
        return civilization.id();
    }

    private CommandPorts.PlayerIdentity resolvePlayer(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            return new CommandPorts.PlayerIdentity(uuid, uuid.toString());
        } catch (IllegalArgumentException ignored) {
            return players.findKnownExact(value).orElseThrow(() -> new IllegalArgumentException("No known player matches " + value + "."));
        }
    }

    private static ChunkKey currentChunk(CommandSender sender) {
        return currentChunk(requirePlayer(sender, "This command needs a player's current chunk."));
    }

    private static ChunkKey currentChunk(Player player) {
        return new ChunkKey(player.getWorld().getUID(), player.getLocation().getChunk().getX(), player.getLocation().getChunk().getZ());
    }

    private static Player requirePlayer(CommandSender sender, String message) {
        if (sender instanceof Player player) return player;
        throw new IllegalArgumentException(message);
    }

    private static UUID actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    private static void requirePermission(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) throw new IllegalArgumentException("Missing permission: " + permission);
    }

    private static List<String> matching(Collection<String> values, String prefix) {
        String normalized = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(Objects::nonNull).filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized))
            .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private static String join(String[] values, int from, int to) {
        return String.join(" ", Arrays.copyOfRange(values, from, to)).trim();
    }

    private static int integer(String value, String label) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(label + " must be a whole number."); }
    }

    private static int positiveInteger(String value, String label) {
        int parsed = integer(value, label);
        if (parsed < 1) throw new IllegalArgumentException(label + " must be at least 1.");
        return parsed;
    }

    private static long positiveLong(String value, String label) {
        long parsed = signedLong(value, label);
        if (parsed < 1) throw new IllegalArgumentException(label + " must be at least 1.");
        return parsed;
    }

    private static long signedLong(String value, String label) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed == 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(label + " must be a non-zero whole number.");
        }
    }

    private static UUID parseUuid(String value, String label) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(label + " is not a UUID."); }
    }

    private static Instant instant(String value, String label) {
        try { return Instant.parse(value); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException(label + " must be an ISO-8601 instant, such as 2026-08-17T12:00:00Z."); }
    }

    private static String title(String value) {
        if (value == null || value.isBlank()) return "Unknown";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1).toLowerCase(Locale.ROOT);
    }

    private static IllegalArgumentException usage(String command) {
        return new IllegalArgumentException("Usage: " + command);
    }

    private static String rootMessage(Throwable failure) {
        Throwable cursor = failure;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private static void line(CommandSender sender, String message) {
        sender.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', message));
    }

    private static void error(CommandSender sender, String message) {
        line(sender, "&c" + message);
    }

    @FunctionalInterface
    public interface ReloadHandler {
        /** Invoked synchronously on the Bukkit command thread. */
        Result reload() throws Exception;
    }
}
