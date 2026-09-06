package io.github.empireage.civilizations.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class MainThreadExecutor {
    private final JavaPlugin plugin;

    public MainThreadExecutor(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public <T> CompletableFuture<T> call(Supplier<T> supplier) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (!plugin.isEnabled()) {
            result.completeExceptionally(new IllegalStateException("The plugin is disabled"));
            return result;
        }
        Runnable task = () -> {
            if (!plugin.isEnabled()) {
                result.completeExceptionally(new IllegalStateException("The plugin was disabled before the main-thread task ran"));
                return;
            }
            try {
                result.complete(supplier.get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            try {
                Bukkit.getScheduler().runTask(plugin, task);
            } catch (RuntimeException schedulerUnavailable) {
                result.completeExceptionally(schedulerUnavailable);
            }
        }
        return result;
    }

    public void run(Runnable task) {
        if (!plugin.isEnabled()) return;
        Runnable guarded = () -> {
            if (plugin.isEnabled()) task.run();
        };
        if (Bukkit.isPrimaryThread()) {
            guarded.run();
        } else {
            try {
                Bukkit.getScheduler().runTask(plugin, guarded);
            } catch (RuntimeException ignored) {
                // Shutdown can race an asynchronous completion. There is no safe
                // Bukkit state to mutate once the plugin has been disabled.
            }
        }
    }
}
