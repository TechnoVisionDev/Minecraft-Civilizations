package io.github.empireage.civilizations.runtime;

import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.service.war.WarItems;
import io.github.empireage.civilizations.service.war.WarService;
import io.github.empireage.civilizations.util.MainThreadExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class WarCoordinator {
    private final WarService wars;
    private final WarItems items;
    private final MainThreadExecutor mainThread;

    public WarCoordinator(JavaPlugin plugin, WarService wars, WarItems items) {
        this.wars = wars;
        this.items = items;
        this.mainThread = new MainThreadExecutor(plugin);
    }

    public CompletableFuture<OperationResult> declare(Player player, String target) {
        int slot = findCharter(player);
        if (slot < 0) return CompletableFuture.completedFuture(OperationResult.denied("A genuine War Charter is required."));
        ItemStack stack = player.getInventory().getItem(slot);
        if (stack.getAmount() == 1) player.getInventory().setItem(slot, null); else stack.setAmount(stack.getAmount() - 1);
        return wars.declare(player.getUniqueId(), target, true).handle((result, error) -> error == null ? result
            : OperationResult.denied("War declaration failed: " + rootMessage(error))).thenCompose(result -> {
            if (result.success()) return CompletableFuture.completedFuture(result);
            return restore(player, items.charter()).thenApply(ignored -> result);
        });
    }

    public CompletableFuture<OperationResult> cancel(Player player) {
        return wars.cancel(player.getUniqueId()).thenCompose(result -> {
            if (!result.success()) return CompletableFuture.completedFuture(result);
            return restore(player, items.charter()).thenApply(ignored -> result);
        });
    }

    private int findCharter(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (items.isCharter(player.getInventory().getItem(slot))) return slot;
        }
        return -1;
    }

    private CompletableFuture<Void> restore(Player player, ItemStack item) {
        return mainThread.call(() -> {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
            leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            return null;
        });
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}
