package io.github.empireage.civilizations.command;

import io.github.empireage.civilizations.config.Messages;
import io.github.empireage.civilizations.service.economy.EconomyService;
import io.github.empireage.civilizations.service.economy.SellCatalog;
import io.github.empireage.civilizations.service.territory.EconomyPort;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** Player-facing bundle shop backed by the configured Vault Economy provider. */
public final class SellCommand implements CommandExecutor, TabCompleter, Listener {
    private static final List<String> SUBCOMMANDS = List.of("hand", "all", "list");

    private final JavaPlugin plugin;
    private final Messages messages;
    private final EconomyService economy;
    private final SellCatalog catalog;
    private final Set<UUID> inFlight = new HashSet<>();

    public SellCommand(JavaPlugin plugin, Messages messages, EconomyService economy) {
        this(plugin, messages, economy, new SellCatalog());
    }

    SellCommand(JavaPlugin plugin, Messages messages, EconomyService economy, SellCatalog catalog) {
        this.plugin = plugin;
        this.messages = messages;
        this.economy = economy;
        this.catalog = catalog;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "players-only");
            return true;
        }
        if (args.length != 1) {
            messages.send(player, "sell-usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "hand" -> sellHand(player);
            case "all" -> sellAll(player);
            case "list" -> openList(player);
            default -> messages.send(player, "sell-usage");
        }
        return true;
    }

    private void sellHand(Player player) {
        int slot = player.getInventory().getHeldItemSlot();
        ItemStack held = player.getInventory().getItem(slot);
        if (held == null || held.getType().isAir() || catalog.offer(held.getType()) == null) {
            messages.send(player, "sell-invalid-hand");
            return;
        }
        sell(player, counts(held), Set.of(slot));
    }

    private void sellAll(Player player) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        Set<Integer> slots = new HashSet<>();
        for (int slot = 0; slot < storage.length; slot++) slots.add(slot);
        sell(player, counts(storage), slots);
    }

    private void sell(Player player, Map<Material, Integer> available, Set<Integer> eligibleSlots) {
        if (!economy.enabled()) {
            messages.send(player, "sell-unavailable");
            return;
        }
        if (!inFlight.add(player.getUniqueId())) {
            messages.send(player, "sell-busy");
            return;
        }

        SellCatalog.Quote quote = catalog.quote(available);
        if (quote.empty()) {
            inFlight.remove(player.getUniqueId());
            messages.send(player, "sell-no-bundles");
            return;
        }

        List<ItemStack> removed;
        try {
            removed = remove(player.getInventory(), quote.removals(), eligibleSlots);
        } catch (RuntimeException failure) {
            inFlight.remove(player.getUniqueId());
            plugin.getLogger().log(Level.SEVERE, "Could not prepare /sell inventory transaction", failure);
            messages.send(player, "sell-failed");
            return;
        }

        UUID operationId = UUID.randomUUID();
        messages.send(player, "sell-processing");
        economy.port().deposit(operationId, player.getUniqueId(), BigDecimal.valueOf(quote.payout()))
            .whenComplete((result, failure) -> runOnMain(() -> finishSale(player, operationId, quote, removed, result, failure)));
    }

    private void finishSale(Player player, UUID operationId, SellCatalog.Quote quote, List<ItemStack> removed,
                            EconomyPort.Result result, Throwable failure) {
        inFlight.remove(player.getUniqueId());
        if (failure != null) {
            restore(player, removed);
            plugin.getLogger().log(Level.WARNING, "Vault /sell payout failed before returning a result", failure);
            messages.send(player, "sell-failed");
            return;
        }
        if (result == null) {
            restore(player, removed);
            messages.send(player, "sell-failed");
            return;
        }
        if (result.ambiguous()) {
            plugin.getLogger().warning("Vault /sell payout " + operationId + " for player "
                + player.getUniqueId() + " has an unknown outcome; sold items were retained to prevent duplication. Provider: "
                + result.providerMessage());
            messages.send(player, "sell-ambiguous", Map.of("operation", operationId));
            return;
        }
        if (!result.success()) {
            restore(player, removed);
            messages.send(player, "sell-failed");
            return;
        }
        messages.send(player, "sell-success", Map.of(
            "items", quote.itemCount(), "bundles", quote.bundleCount(), "coins", quote.payout()));
    }

    private static List<ItemStack> remove(PlayerInventory inventory, Map<Material, Integer> requested,
                                          Set<Integer> eligibleSlots) {
        Map<Material, Integer> remaining = new HashMap<>(requested);
        ItemStack[] storage = inventory.getStorageContents();
        List<ItemStack> removed = new ArrayList<>();
        for (int slot = 0; slot < storage.length; slot++) {
            if (!eligibleSlots.contains(slot)) continue;
            ItemStack stack = storage[slot];
            if (stack == null) continue;
            int needed = remaining.getOrDefault(stack.getType(), 0);
            if (needed <= 0) continue;
            int amount = Math.min(needed, stack.getAmount());
            ItemStack taken = stack.clone();
            taken.setAmount(amount);
            removed.add(taken);
            if (amount == stack.getAmount()) storage[slot] = null;
            else {
                ItemStack remainder = stack.clone();
                remainder.setAmount(stack.getAmount() - amount);
                storage[slot] = remainder;
            }
            remaining.put(stack.getType(), needed - amount);
        }
        if (remaining.values().stream().anyMatch(value -> value != 0)) {
            throw new IllegalStateException("Inventory changed while preparing sale");
        }
        inventory.setStorageContents(storage);
        return removed;
    }

    private void restore(Player player, List<ItemStack> removed) {
        for (ItemStack item : removed) {
            player.getInventory().addItem(item).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }

    private void openList(Player player) {
        SellListHolder holder = new SellListHolder();
        Inventory menu = Bukkit.createInventory(holder, 27, "Sell Prices");
        holder.inventory = menu;
        menu.setItem(4, item(Material.GOLD_INGOT, "&6&lVault Sell Prices", 1, List.of(
            "&7Use &e/sell hand &7or &e/sell all&7.",
            "&8Only complete bundles are sold.")));
        int slot = 9;
        for (SellCatalog.Offer offer : catalog.offers()) {
            menu.setItem(slot++, item(offer.material(), "&e" + offer.displayName(), offer.bundleSize(), List.of(
                "&7Bundle: &f" + offer.bundleSize(),
                "&7Worth: &6" + offer.payout() + " coins")));
        }
        player.openInventory(menu);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SellListHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SellListHolder) event.setCancelled(true);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(value -> value.startsWith(prefix)).toList();
    }

    private static Map<Material, Integer> counts(ItemStack... contents) {
        Map<Material, Integer> counts = new HashMap<>();
        for (ItemStack stack : contents) {
            if (stack == null || stack.getType().isAir()) continue;
            counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
        }
        return counts;
    }

    private void runOnMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }

    private static ItemStack item(Material material, String name, int amount, List<String> lore) {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Messages.color(name));
        meta.setLore(lore.stream().map(Messages::color).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static final class SellListHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
