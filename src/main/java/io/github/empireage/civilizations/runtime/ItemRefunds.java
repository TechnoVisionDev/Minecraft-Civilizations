package io.github.empireage.civilizations.runtime;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Local refund outbox, available even while MySQL is down. Prepare before a debit;
 * make refundable only after a proven rollback. HELD entries are evidence for
 * reconciliation, never automatic refunds of an uncertain database operation.
 */
public final class ItemRefunds {
    private static final Gson GSON = new Gson();
    private static final byte DEBITED = 1;
    private static final byte DELIVERED = 2;
    private final JavaPlugin plugin;
    private final String scope;

    public ItemRefunds(JavaPlugin plugin, String scope) {
        this.plugin = plugin;
        this.scope = scope;
    }

    public Ticket prepare(UUID operation, UUID player, List<ItemStack> items) {
        requireMain();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("items", items.stream().map(ItemStack::clone).toList());
        Ticket ticket = new Ticket(operation, player, yaml.saveToString(), "HELD");
        write(ticket);
        return ticket;
    }

    /** Receipt and inventory debit share one player save, before any database work. */
    public void debit(Player player, Ticket ticket, ItemStack[] after) {
        requireMain();
        ItemStack[] before = copy(player.getInventory().getStorageContents());
        NamespacedKey receipt = key(ticket.operation());
        try {
            player.getPersistentDataContainer().set(receipt, PersistentDataType.BYTE, DEBITED);
            player.getInventory().setStorageContents(after);
            player.saveData();
        } catch (RuntimeException failure) {
            // No database work has started. Restore the original inventory if saving the debit fails.
            try {
                player.getInventory().setStorageContents(before);
                player.getPersistentDataContainer().remove(receipt);
                player.saveData();
                complete(ticket);
            } catch (RuntimeException restorationFailure) {
                failure.addSuppressed(restorationFailure);
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Could not restore unsaved item debit " + ticket.operation(), failure);
            }
            throw failure;
        }
    }

    /** Safe from a database completion thread: the serialized items were captured on the main thread. */
    public void refundable(Ticket ticket) {
        write(new Ticket(ticket.operation(), ticket.player(), ticket.items(), "REFUND"));
    }

    public void complete(Ticket ticket) {
        try {
            Files.deleteIfExists(file(ticket.operation()));
        } catch (IOException failure) {
            // HELD is not eligible for delivery. Retaining this record cannot duplicate items.
            plugin.getLogger().warning("Could not remove settled item journal " + ticket.operation() + ": " + failure.getMessage());
        }
    }

    /** Restores all known refunds for this player, or keeps them queued if the inventory is full. */
    public boolean restore(Player player) {
        requireMain();
        if (!player.isOnline()) return false;
        boolean restored = false;
        for (Ticket ticket : pending(player.getUniqueId())) {
            NamespacedKey receipt = key(ticket.operation());
            Byte state = player.getPersistentDataContainer().get(receipt, PersistentDataType.BYTE);
            if (state == null || state == DELIVERED) {
                // No saved debit, or a refund already included in the saved player inventory.
                complete(ticket);
                continue;
            }
            List<ItemStack> items = decode(ticket.items());
            ItemStack[] before = copy(player.getInventory().getStorageContents());
            ItemStack[] after = fit(before, items);
            if (after == null) {
                player.sendMessage("An item refund is waiting. Free inventory space and rejoin or retry the action to collect it.");
                continue;
            }
            player.getInventory().setStorageContents(after);
            player.getPersistentDataContainer().set(receipt, PersistentDataType.BYTE, DELIVERED);
            try {
                player.saveData();
            } catch (RuntimeException failure) {
                // Keep the outbox entry; do not leave an unsaved receipt suppressing a retry.
                player.getInventory().setStorageContents(before);
                player.getPersistentDataContainer().set(receipt, PersistentDataType.BYTE, DEBITED);
                throw failure;
            }
            complete(ticket);
            restored = true;
        }
        cleanupReceipts(player);
        return restored;
    }

    public boolean hasPending(UUID player) {
        return !pending(player).isEmpty();
    }

    private List<Ticket> pending(UUID player) {
        List<Ticket> tickets = new ArrayList<>();
        Path directory = directory();
        if (!Files.exists(directory)) return tickets;
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                Ticket ticket;
                try {
                    ticket = GSON.fromJson(Files.readString(path), Ticket.class);
                } catch (java.nio.file.NoSuchFileException settledConcurrently) {
                    // Another player's successful database completion can remove a HELD file.
                    continue;
                }
                if (ticket == null || ticket.operation() == null || ticket.player() == null || ticket.items() == null
                    || !("HELD".equals(ticket.state()) || "REFUND".equals(ticket.state()))) {
                    throw new IOException("Invalid item refund journal: " + path);
                }
                if (ticket.player().equals(player) && "REFUND".equals(ticket.state())) tickets.add(ticket);
            }
            return tickets;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read pending item refunds", failure);
        }
    }

    private void write(Ticket ticket) {
        try {
            Files.createDirectories(directory());
            Path temporary = Files.createTempFile(directory(), "refund-", ".tmp");
            try {
                byte[] bytes = GSON.toJson(ticket).getBytes(StandardCharsets.UTF_8);
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    ByteBuffer data = ByteBuffer.wrap(bytes);
                    while (data.hasRemaining()) channel.write(data);
                    channel.force(true);
                }
                // An unsupported atomic move is a failure, never a partial live journal.
                Files.move(temporary, file(ticket.operation()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not persist item recovery operation " + ticket.operation(), failure);
        }
    }

    private List<ItemStack> decode(String serialized) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(serialized);
            List<?> raw = yaml.getList("items");
            if (raw == null) throw new IllegalStateException("Missing refund items");
            List<ItemStack> items = new ArrayList<>();
            for (Object value : raw) {
                if (!(value instanceof ItemStack stack) || stack.getAmount() <= 0) {
                    throw new IllegalStateException("Invalid refund item");
                }
                items.add(stack);
            }
            return items;
        } catch (org.bukkit.configuration.InvalidConfigurationException failure) {
            throw new IllegalStateException("Could not decode item refund", failure);
        }
    }

    // Plan the entire delivery before modifying inventory. Never drop refunds into
    // the world: world saves cannot commit atomically with a player receipt.
    static ItemStack[] fit(ItemStack[] contents, List<ItemStack> items) {
        ItemStack[] result = copy(contents);
        for (ItemStack item : items) {
            int remaining = item.getAmount();
            for (ItemStack current : result) {
                if (current == null || !current.isSimilar(item)) continue;
                int moved = Math.min(remaining, Math.max(0, current.getMaxStackSize() - current.getAmount()));
                current.setAmount(current.getAmount() + moved);
                remaining -= moved;
            }
            for (int slot = 0; remaining > 0 && slot < result.length; slot++) {
                if (result[slot] != null && result[slot].getType() != Material.AIR && result[slot].getAmount() > 0) continue;
                ItemStack added = item.clone();
                int moved = Math.min(remaining, item.getMaxStackSize());
                added.setAmount(moved);
                result[slot] = added;
                remaining -= moved;
            }
            if (remaining > 0) return null;
        }
        return result;
    }

    private static ItemStack[] copy(ItemStack[] contents) {
        ItemStack[] copied = contents.clone();
        for (int slot = 0; slot < copied.length; slot++) if (copied[slot] != null) copied[slot] = copied[slot].clone();
        return copied;
    }

    private void cleanupReceipts(Player player) {
        boolean changed = false;
        for (NamespacedKey receipt : player.getPersistentDataContainer().getKeys()) {
            String prefix = "refund-" + scope + "-";
            if (!receipt.getNamespace().equals(plugin.getName().toLowerCase(java.util.Locale.ROOT))
                || !receipt.getKey().startsWith(prefix)) continue;
            UUID id = UUID.fromString(receipt.getKey().substring(prefix.length()));
            if (!Files.exists(file(id))) {
                player.getPersistentDataContainer().remove(receipt);
                changed = true;
            }
        }
        if (changed) player.saveData();
    }

    private Path directory() { return plugin.getDataFolder().toPath().resolve("item-refunds").resolve(scope); }
    private Path file(UUID id) { return directory().resolve(id + ".json"); }
    private NamespacedKey key(UUID id) { return new NamespacedKey(plugin, "refund-" + scope + "-" + id); }
    private static void requireMain() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Item inventory recovery must run on the main thread");
    }
    public record Ticket(UUID operation, UUID player, String items, String state) {}
}
