package io.github.empireage.civilizations.listener.war;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.domain.Claim;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.War;
import io.github.empireage.civilizations.service.war.WarItems;
import io.github.empireage.civilizations.service.war.WarService;
import io.github.empireage.civilizations.util.MainThreadExecutor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class WarListener implements Listener {
    private final JavaPlugin plugin;
    private final StateCache cache;
    private final WarService wars;
    private final WarItems items;
    private final Settings.War settings;
    private final MainThreadExecutor mainThread;

    public WarListener(JavaPlugin plugin, StateCache cache, WarService wars, WarItems items, Settings.War settings) {
        this.plugin = plugin;
        this.cache = cache;
        this.wars = wars;
        this.items = items;
        this.settings = settings;
        this.mainThread = new MainThreadExecutor(plugin);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onStandardPlace(BlockPlaceEvent event) {
        if (!items.isStandard(event.getItemInHand())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage("War Standards are retired. War is a timed window for fighting, breaking, and looting.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void recordSiegePlacement(BlockPlaceEvent event) {
        if (items.isStandard(event.getItemInHand())) return;
        Player player = event.getPlayer();
        Member member = cache.snapshot().member(player.getUniqueId());
        if (member == null) return;
        Block block = event.getBlockPlaced();
        Claim claim = cache.snapshot().claim(key(block));
        if (claim == null || !wars.mayBuildInEnemy(player.getUniqueId(), claim, block.getType(), true)) return;
        War war = cache.snapshot().warFor(member.civilizationId());
        if (war == null) return;
        if (isProtected(event.getBlockReplacedState())) {
            event.setCancelled(true);
            player.sendMessage("Protected blocks and containers cannot be replaced by temporary siege blocks.");
            return;
        }
        BlockData intended = block.getBlockData().clone();
        BlockData replaced = event.getBlockReplacedState().getBlockData().clone();
        ItemStack refund = one(event.getItemInHand());
        event.setCancelled(true);
        if (!consumeOne(player, event.getHand(), event.getItemInHand())) {
            player.sendMessage("The siege block could not be consumed from your hand.");
            return;
        }
        wars.recordTemporaryBlock(player.getUniqueId(), war.id(), block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(),
            replaced.getAsString(), intended.getAsString()).whenComplete((recordId, error) -> mainThread.run(() -> {
                if (error != null || recordId == null) {
                    restore(player, refund);
                    player.sendMessage("The siege block was not placed because its cleanup record could not be stored.");
                    return;
                }
                Claim currentClaim = cache.snapshot().claim(key(block));
                if (!sameBlockData(block.getBlockData(), replaced) || currentClaim == null
                    || !wars.mayBuildInEnemy(player.getUniqueId(), currentClaim, intended.getMaterial(), true)) {
                    discardTemporary(recordId, player, refund,
                        "The target or campaign changed before placement; the siege block was returned.");
                    return;
                }
                try {
                    block.setBlockData(intended, false);
                } catch (RuntimeException worldFailure) {
                    if (sameBlockData(block.getBlockData(), intended)) block.setBlockData(replaced, false);
                    discardTemporary(recordId, player, refund,
                        "The siege block could not be installed in the world and was returned.");
                }
            }));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void stopLateWarTnt(ExplosionPrimeEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed tnt)) return;
        Player source = sourcePlayer(tnt.getSource());
        Claim claim = cache.snapshot().claim(key(tnt.getLocation()));
        if (claim == null) return;
        if (source == null || !wars.mayBuildInEnemy(source.getUniqueId(), claim, Material.TNT, false)) {
            event.setCancelled(true);
            tnt.remove();
        }
    }

    private static Player sourcePlayer(Entity entity) {
        return entity instanceof Player player ? player : null;
    }

    private static boolean consumeOne(Player player, EquipmentSlot hand, ItemStack expected) {
        ItemStack held = hand == EquipmentSlot.OFF_HAND ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || held.getAmount() < 1 || expected == null || !held.isSimilar(expected)) return false;
        if (held.getAmount() == 1) {
            if (hand == EquipmentSlot.OFF_HAND) player.getInventory().setItemInOffHand(null);
            else player.getInventory().setItemInMainHand(null);
        } else {
            held.setAmount(held.getAmount() - 1);
        }
        return true;
    }

    private static void restore(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        player.saveData();
    }

    private void discardTemporary(long recordId, Player player, ItemStack refund, String message) {
        restore(player, refund);
        player.sendMessage(message);
        wars.markCleaned(new WarService.CollectionIds(java.util.List.of(recordId))).exceptionally(error -> {
            plugin.getLogger().warning("Could not mark unused siege placement #" + recordId + " handled: " + error.getMessage());
            return null;
        });
    }

    private boolean isProtected(BlockState state) {
        return state instanceof InventoryHolder || state.getType() == Material.ENDER_CHEST
            || settings.alwaysProtectedMaterials().contains(state.getType());
    }

    private static boolean sameBlockData(BlockData first, BlockData second) {
        return first != null && second != null && first.getAsString().equals(second.getAsString());
    }

    private static ItemStack one(ItemStack source) {
        ItemStack item = source.clone();
        item.setAmount(1);
        return item;
    }

    private static ChunkKey key(Block block) {
        return new ChunkKey(block.getWorld().getUID(), block.getX() >> 4, block.getZ() >> 4);
    }

    private static ChunkKey key(Location location) {
        return new ChunkKey(location.getWorld().getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }
}
