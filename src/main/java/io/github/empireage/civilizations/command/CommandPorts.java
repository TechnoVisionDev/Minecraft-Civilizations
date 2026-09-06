package io.github.empireage.civilizations.command;

import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.service.war.WarItems;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Small main-thread integration points used by {@link CivCommand}. Database and
 * policy work stays in the domain services; these ports own Bukkit side effects
 * which cannot safely be performed from a database completion thread.
 */
public final class CommandPorts {
    private CommandPorts() {}

    @FunctionalInterface
    public interface FoundingPort {
        CompletableFuture<OperationResult> create(Player player, String civilizationName);
    }

    @FunctionalInterface
    public interface HomePort {
        OperationResult teleport(Player player);
    }

    @FunctionalInterface
    public interface TravelPort {
        OperationResult teleport(Player player, String target);
    }

    public interface ChatPort {
        CompletableFuture<OperationResult> toggle(Player player);

        OperationResult send(Player player, String message);

        static ChatPort of(Function<Player, CompletableFuture<OperationResult>> toggle,
                           BiFunction<Player, String, OperationResult> send) {
            Objects.requireNonNull(toggle, "toggle");
            Objects.requireNonNull(send, "send");
            return new ChatPort() {
                @Override
                public CompletableFuture<OperationResult> toggle(Player player) {
                    return toggle.apply(player);
                }

                @Override
                public OperationResult send(Player player, String message) {
                    return send.apply(player, message);
                }
            };
        }
    }

    public interface PlayerDirectoryPort {
        Optional<PlayerIdentity> findKnownExact(String name);

        Collection<String> knownNames();

        /** Bukkit's persisted player list plus currently online players. Call on the server thread. */
        static PlayerDirectoryPort bukkit() {
            return new PlayerDirectoryPort() {
                @Override
                public Optional<PlayerIdentity> findKnownExact(String name) {
                    if (name == null || name.isBlank()) return Optional.empty();
                    Player online = Bukkit.getPlayerExact(name);
                    if (online != null) return Optional.of(new PlayerIdentity(online.getUniqueId(), online.getName()));
                    for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                        if (player.getName() != null && player.getName().equalsIgnoreCase(name)) {
                            return Optional.of(new PlayerIdentity(player.getUniqueId(), player.getName()));
                        }
                    }
                    return Optional.empty();
                }

                @Override
                public Collection<String> knownNames() {
                    Map<String, String> names = new LinkedHashMap<>();
                    for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                        if (player.getName() != null) names.put(player.getName().toLowerCase(Locale.ROOT), player.getName());
                    }
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        names.put(player.getName().toLowerCase(Locale.ROOT), player.getName());
                    }
                    return List.copyOf(names.values());
                }
            };
        }
    }

    public record PlayerIdentity(UUID id, String name) {
        public PlayerIdentity {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }
    }

    /** Consumes and restores authentic War Charter items on the main thread. */
    public interface WarCharterPort {
        boolean consume(Player player);

        void restore(Player player);

        static WarCharterPort inventoryBacked(WarItems items) {
            Objects.requireNonNull(items, "items");
            return new WarCharterPort() {
                @Override
                public boolean consume(Player player) {
                    ItemStack[] contents = player.getInventory().getStorageContents();
                    for (int slot = 0; slot < contents.length; slot++) {
                        ItemStack item = contents[slot];
                        if (!items.isCharter(item)) continue;
                        if (item.getAmount() <= 1) contents[slot] = null;
                        else item.setAmount(item.getAmount() - 1);
                        player.getInventory().setStorageContents(contents);
                        player.saveData();
                        return true;
                    }
                    return false;
                }

                @Override
                public void restore(Player player) {
                    ItemStack charter = items.charter();
                    Map<Integer, ItemStack> overflow = player.getInventory().addItem(charter);
                    overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                    player.saveData();
                }
            };
        }
    }

    /** Delegates the complete `/civ admin ...` subtree to the administrator command implementation. */
    public interface AdminCommandPort {
        boolean execute(CommandSender sender, String[] arguments);

        List<String> complete(CommandSender sender, String[] arguments);

        AdminCommandPort NONE = new AdminCommandPort() {
            @Override
            public boolean execute(CommandSender sender, String[] arguments) {
                sender.sendMessage("Administrator commands are unavailable.");
                return true;
            }

            @Override
            public List<String> complete(CommandSender sender, String[] arguments) {
                return List.of();
            }
        };
    }

    public record Ports(FoundingPort founding, HomePort home, TravelPort travel, ChatPort chat,
                        PlayerDirectoryPort players, WarCharterPort warCharters,
                        AdminCommandPort admin) {
        public Ports {
            Objects.requireNonNull(founding, "founding");
            Objects.requireNonNull(home, "home");
            Objects.requireNonNull(travel, "travel");
            Objects.requireNonNull(chat, "chat");
            Objects.requireNonNull(players, "players");
            Objects.requireNonNull(warCharters, "warCharters");
            admin = admin == null ? AdminCommandPort.NONE : admin;
        }
    }
}
