package io.github.empireage.civilizations.command;

import io.github.empireage.civilizations.config.Messages;
import io.github.empireage.civilizations.config.ReligionCatalog;
import io.github.empireage.civilizations.service.religion.ReligionService;
import io.github.empireage.civilizations.util.MainThreadExecutor;
import io.github.empireage.civilizations.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** Pantheon menu and inventory-backed sacrifice command. */
public final class ReligionCommand implements CommandExecutor, TabCompleter, Listener {
    private static final List<Integer> GOD_SLOTS = List.of(10, 11, 12, 14, 15, 16);

    private final JavaPlugin plugin;
    private final ReligionCatalog catalog;
    private final ReligionService service;
    private final MainThreadExecutor mainThread;
    private final Map<UUID, String> selected = new HashMap<>();
    private final Set<UUID> inFlight = new HashSet<>();

    public ReligionCommand(JavaPlugin plugin, ReligionCatalog catalog, ReligionService service) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.service = service;
        this.mainThread = new MainThreadExecutor(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players may use the pantheon.");
            return true;
        }
        if (command.getName().equalsIgnoreCase("religion")) {
            if (args.length == 0) openPantheon(player);
            else if (args.length == 1) select(player, args[0]);
            else player.sendMessage(ChatColor.RED + "Usage: /religion [god]");
            return true;
        }
        if (args.length > 1) {
            player.sendMessage(ChatColor.RED + "Usage: /sacrifice [god]");
            return true;
        }
        String godKey = args.length == 1 ? args[0] : selected.get(player.getUniqueId());
        ReligionCatalog.God god = catalog.get(godKey);
        if (god == null) {
            player.sendMessage(ChatColor.RED + "Choose a god in /religion first, or use /sacrifice <god>.");
            return true;
        }
        sacrifice(player, god);
        return true;
    }

    private void openPantheon(Player player) {
        Instant now = Instant.now();
        service.progress(player.getUniqueId()).whenComplete((progress, error) -> mainThread.run(() -> {
            if (error != null) {
                player.sendMessage(ChatColor.RED + "The pantheon cannot be reached while storage is unavailable.");
                return;
            }
            if (!player.isOnline()) return;
            PantheonHolder holder = new PantheonHolder();
            Inventory menu = Bukkit.createInventory(holder, 27, "The Greek Pantheon");
            holder.inventory = menu;
            int index = 0;
            for (ReligionCatalog.God god : catalog.gods().values()) {
                if (index >= GOD_SLOTS.size()) break;
                int slot = GOD_SLOTS.get(index++);
                holder.gods.put(slot, god.key());
                List<String> lore = new ArrayList<>();
                god.description().forEach(line -> lore.add("&7" + line));
                lore.add("");
                lore.add("&eOffering: &f" + readable(god.offering()));
                ReligionService.GodProgress standing = progress.getOrDefault(god.key(),
                    new ReligionService.GodProgress(1, null));
                lore.addAll(favorLore(standing.favorLevel()));
                lore.add("&eBlessing: &f" + blessing(god));
                Instant available = standing.availableAt();
                lore.add(available == null || !now.isBefore(available)
                    ? "&aAvailable — click to choose"
                    : "&cAvailable " + TimeUtil.relative(available, now));
                menu.setItem(slot, item(god.icon(), "&6&l" + god.name(), lore));
            }
            player.openInventory(menu);
        }));
    }

    private void select(Player player, String rawKey) {
        ReligionCatalog.God god = catalog.get(rawKey);
        if (god == null) {
            player.sendMessage(ChatColor.RED + "Unknown god. Open /religion to view the pantheon.");
            return;
        }
        selected.put(player.getUniqueId(), god.key());
        player.sendMessage(ChatColor.GOLD + "You approach " + god.name() + ". Hold a stack of "
            + readable(god.offering()) + " and use /sacrifice. The entire held stack will be consumed.");
    }

    private void sacrifice(Player player, ReligionCatalog.God god) {
        UUID playerId = player.getUniqueId();
        if (!service.available()) {
            player.sendMessage(ChatColor.RED + "Sacrifices are unavailable while storage is offline.");
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            player.sendMessage(ChatColor.RED + "Sacrifices must be made in Survival or Adventure mode.");
            return;
        }
        if (!inFlight.add(playerId)) {
            player.sendMessage(ChatColor.RED + "Your previous sacrifice is still being judged.");
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || held.getAmount() <= 0) {
            inFlight.remove(playerId);
            player.sendMessage(ChatColor.RED + "Hold the stack you intend to sacrifice in your main hand.");
            return;
        }
        ItemStack removed = held.clone();
        player.getInventory().setItemInMainHand(null);
        player.saveData();
        Instant now = Instant.now();
        service.record(playerId, god.key(), removed.getType().getKey().toString(), removed.getAmount(),
            removed.getType() == god.offering(), now).whenComplete((result, error) -> mainThread.run(() -> {
                inFlight.remove(playerId);
                if (error != null) {
                    restore(player, removed);
                    plugin.getLogger().log(Level.WARNING, "Sacrifice persistence failed", error);
                    player.sendMessage(ChatColor.RED + "The sacrifice could not be recorded; your items were returned.");
                    return;
                }
                if (!result.recorded() && result.safeToRestore()) {
                    restore(player, removed);
                    if (result.availableAt() != null) player.sendMessage(ChatColor.RED + god.name() + " will hear you "
                        + TimeUtil.relative(result.availableAt(), Instant.now()) + ". Your items were returned.");
                    else {
                        plugin.getLogger().log(Level.WARNING, "Sacrifice transaction rolled back", result.failure());
                        player.sendMessage(ChatColor.RED + "The sacrifice failed safely; your items were returned.");
                    }
                    return;
                }
                if (!result.recorded()) {
                    plugin.getLogger().log(Level.SEVERE, "Sacrifice outcome is uncertain; operation "
                        + result.operationId(), result.failure());
                    player.sendMessage(ChatColor.RED + "The sacrifice outcome could not be confirmed. The stack was retained "
                        + "to prevent duplication; ask an administrator to review operation " + result.operationId() + ".");
                    return;
                }
                if (result.success()) {
                    accept(player, god, result.roll(), removed.getAmount());
                    player.sendMessage(ChatColor.GOLD + "Favor with " + god.name() + ": level "
                        + result.favorLevel() + "/" + ReligionService.MAX_FAVOR_LEVEL
                        + ". Future divine rolls: 1-" + ReligionService.maxRoll(result.favorLevel()) + ".");
                } else reject(player, god, result.roll(), removed);
            }));
    }

    private void accept(Player player, ReligionCatalog.God god, int roll, int amount) {
        int ticks = (int) Math.min(Integer.MAX_VALUE, catalog.buffDuration().toSeconds() * 20L);
        if (player.isOnline()) for (ReligionCatalog.Buff buff : god.buffs()) {
            PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(buff.effect()));
            if (type == null) {
                plugin.getLogger().warning("Unknown configured potion effect " + buff.effect() + " for " + god.key());
                continue;
            }
            player.addPotionEffect(new PotionEffect(type, ticks, buff.amplifier(), true, true, true));
        }
        player.sendMessage(ChatColor.GREEN + god.name() + " accepts " + amount + " offerings (divine roll "
            + roll + ") and grants: " + blessing(god) + ".");
    }

    private void reject(Player player, ReligionCatalog.God god, int roll, ItemStack removed) {
        if (player.isOnline()) player.getWorld().strikeLightning(player.getLocation());
        String reason = removed.getType() != god.offering()
            ? readable(removed.getType()) + " is not the required " + readable(god.offering())
            : removed.getAmount() + " did not exceed the divine roll of " + roll;
        player.sendMessage(ChatColor.RED + god.name() + " rejects the offering: " + reason
            + ". The stack is consumed and no blessing is granted.");
    }

    static List<String> favorLore(int level) {
        return List.of(
            "&eFavor: &fLevel " + level + "/" + ReligionService.MAX_FAVOR_LEVEL,
            "&eTrial: &fYour stack must exceed a roll of 1-" + ReligionService.maxRoll(level),
            level < ReligionService.MAX_FAVOR_LEVEL
                ? "&aNext success: level " + (level + 1) + " (rolls 1-" + ReligionService.maxRoll(level + 1) + ")"
                : "&aMaximum favor reached",
            "&7The entire held stack is consumed.");
    }

    private static void restore(Player player, ItemStack item) {
        player.getInventory().addItem(item).values().forEach(leftover ->
            player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        player.saveData();
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PantheonHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String godKey = holder.gods.get(event.getRawSlot());
        if (godKey == null) return;
        player.closeInventory();
        select(player, godKey);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof PantheonHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selected.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return catalog.gods().keySet().stream().filter(key -> key.startsWith(prefix)).sorted().toList();
    }

    private static String blessing(ReligionCatalog.God god) {
        return god.buffs().stream().map(buff -> title(buff.effect()) + " " + (buff.amplifier() + 1))
            .collect(java.util.stream.Collectors.joining(" + "));
    }

    private static String readable(Material material) {
        return title(material.getKey().getKey());
    }

    private static String title(String key) {
        String[] words = key.replace('-', '_').split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return result.toString();
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Messages.color(name));
        meta.setLore(lore.stream().map(Messages::color).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static final class PantheonHolder implements InventoryHolder {
        private final Map<Integer, String> gods = new HashMap<>();
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
