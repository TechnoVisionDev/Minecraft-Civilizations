package io.github.empireage.civilizations.runtime;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.service.economy.PlotTaxService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Main-thread menu and notification effects around the asynchronous tax service. */
public final class PlotTaxRuntime implements Listener, AutoCloseable {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, uuuu HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private final JavaPlugin plugin;
    private final StateCache cache;
    private final PlotTaxService taxes;
    private final Set<UUID> notifying = ConcurrentHashMap.newKeySet();
    private BukkitTask task;
    private volatile boolean closed;

    public PlotTaxRuntime(JavaPlugin plugin, StateCache cache, PlotTaxService taxes) {
        this.plugin = plugin;
        this.cache = cache;
        this.taxes = taxes;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> taxes.tick().whenComplete((ignored, failure) -> {
            if (failure != null) plugin.getLogger().log(Level.WARNING, "Weekly plot tax processing will resume on the next scan.", failure);
            onMain(() -> Bukkit.getOnlinePlayers().forEach(this::notifyPlayer));
        }), 20L * 30, 20L * 60);
    }

    public void open(Player player) { open(player, 0); }

    private void open(Player player, int requestedPage) {
        taxes.overview(player.getUniqueId()).thenAccept(overview -> onMain(() -> show(player, overview, requestedPage)))
            .exceptionally(failure -> {
                onMain(() -> { if (player.isOnline()) player.sendMessage(color("&cCould not load plot taxes. Please try again.")); });
                return null;
            });
    }

    private void show(Player player, PlotTaxService.TaxOverview overview, int requestedPage) {
        var member = cache.snapshot().member(player.getUniqueId());
        if (!player.isOnline() || member == null || member.civilizationId() != overview.civilizationId()) return;
        int pages = Math.max(1, (overview.plots().size() + 27) / 28);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        Holder holder = new Holder(player.getUniqueId(), page, pages);
        Inventory inventory = Bukkit.createInventory(holder, 54, "Weekly Plot Taxes");
        holder.inventory = inventory;
        for (int slot = 0; slot < 54; slot++) inventory.setItem(slot, item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of()));
        BigDecimal nextTotal = overview.plots().stream().map(PlotTaxService.PlotTaxView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        inventory.setItem(4, item(Material.GOLD_INGOT, "&6Weekly Plot Taxes", List.of(
            "&7Civilization rate: &f$" + overview.rate() + " per plot / week",
            "&7Your private plots: &f" + overview.plots().size(),
            "&7Your scheduled charges: &f$" + nextTotal.setScale(2),
            overview.enabled() ? "&7Payment is deducted automatically, even while offline." : "&eCollection paused: economy unavailable or disabled.",
            "&cInsufficient funds: the affected plot returns to the civ.",
            "&7Public and capital plots are exempt.",
            "&7Page " + (page + 1) + " / " + pages)));
        for (int index = page * 28; index < Math.min(overview.plots().size(), (page + 1) * 28); index++) {
            var plot = overview.plots().get(index);
            List<String> lore = new ArrayList<>();
            lore.add("&7World: &f" + plot.world());
            lore.add("&7Chunk: &f" + plot.x() + ", " + plot.z());
            lore.add("&7Next weekly charge: &f$" + plot.amount());
            lore.add("&7Due: &f" + (plot.due() == null ? "One week after tax enrollment" : DATE.format(plot.due())));
            if (plot.paymentState() != null) lore.add(plot.paymentState().contains("IN_FLIGHT")
                ? "&ePayment needs administrator review; ownership is protected."
                : "&ePayment processing; no further bill will be issued yet.");
            lore.add("&7Paid taxes go to the civilization treasury.");
            lore.add("&cKeep enough money available to retain this plot.");
            int local = index % 28;
            inventory.setItem(10 + (local / 7) * 9 + local % 7, item(Material.MAP,
                "&e" + (plot.label() == null || plot.label().isBlank() ? "Plot " + plot.x() + ", " + plot.z() : plot.label()), lore));
        }
        if (overview.plots().isEmpty()) inventory.setItem(22, item(Material.PAPER, "&aNo taxable plots", List.of("&7You do not owe recurring plot taxes.")));
        inventory.setItem(48, item(Material.BOOK, "&eTax policy", List.of(
            "&7Every civilization starts at $0 per plot / week.",
            "&7New purchases have a full week before payment.",
            "&7Rate changes allow at least seven days before charging.",
            overview.leader() ? "&eSet the rate: /civ taxes set <amount>" : "&7Only the civilization leader can change the rate.",
            "&7Set the rate to 0 to stop future tax bills.")));
        inventory.setItem(50, item(Material.SUNFLOWER, "&eRefresh", List.of()));
        if (page > 0) inventory.setItem(45, item(Material.ARROW, "&ePrevious page", List.of()));
        if (page + 1 < pages) inventory.setItem(53, item(Material.ARROW, "&eNext page", List.of()));
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.getUniqueId().equals(holder.viewer)) return;
        int slot = event.getRawSlot();
        int nextPage = slot == 45 && holder.page > 0 ? holder.page - 1
            : slot == 53 && holder.page + 1 < holder.pages ? holder.page + 1 : slot == 50 ? holder.page : -1;
        if (nextPage >= 0) Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == holder) open(player, nextPage);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        notifyPlayer(player);
        if (!cache.ready() || cache.snapshot().member(player.getUniqueId()) == null) return;
        taxes.overview(player.getUniqueId()).thenAccept(overview -> onMain(() -> {
            if (!player.isOnline() || overview.plots().isEmpty()) return;
            if (overview.rate().signum() > 0 || overview.plots().stream().anyMatch(plot -> plot.amount().signum() > 0))
                player.sendMessage(color("&eYour private plots have automatic weekly taxes. Current civ rate: $"
                    + overview.rate() + " per plot. View your charges and due dates with /civ taxes."));
        })).exceptionally(error -> null);
    }

    private void notifyPlayer(Player player) {
        if (!cache.ready() || !player.isOnline() || !notifying.add(player.getUniqueId())) return;
        taxes.notices(player.getUniqueId()).whenComplete((notices, failure) -> onMain(() -> {
            if (failure != null || !player.isOnline()) { notifying.remove(player.getUniqueId()); return; }
            for (var notice : notices) {
                String location = notice.world() + " (" + notice.x() + ", " + notice.z() + ")";
                player.sendMessage(color(notice.status().equals("PAID")
                    ? "&aPaid $" + notice.amount() + " weekly plot tax for " + location + "."
                    : "&cYour plot at " + location + " was reclaimed by your civilization because your balance could not cover its $"
                        + notice.amount() + " weekly tax. The buildings remain intact."));
            }
            taxes.acknowledge(player.getUniqueId(), notices).whenComplete((ignored, error) -> notifying.remove(player.getUniqueId()));
        }));
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack result = new ItemStack(material);
        var meta = result.getItemMeta();
        meta.setDisplayName(color(name));
        meta.setLore(lore.stream().map(PlotTaxRuntime::color).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        result.setItemMeta(meta);
        return result;
    }

    private void onMain(Runnable action) {
        if (closed || !plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) action.run();
        else try { Bukkit.getScheduler().runTask(plugin, () -> { if (!closed && plugin.isEnabled()) action.run(); }); }
        catch (RuntimeException ignored) { /* Shutdown may race an asynchronous response. */ }
    }

    private static String color(String value) { return ChatColor.translateAlternateColorCodes('&', value); }

    @Override public void close() { closed = true; if (task != null) task.cancel(); taxes.close(); }

    private static final class Holder implements InventoryHolder {
        private final UUID viewer;
        private final int page;
        private final int pages;
        private Inventory inventory;
        private Holder(UUID viewer, int page, int pages) { this.viewer = viewer; this.page = page; this.pages = pages; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
