package io.github.empireage.civilizations.command;

import io.github.empireage.civilizations.runtime.ChatChannel;
import io.github.empireage.civilizations.runtime.CivilizationChat;
import io.github.empireage.civilizations.util.MainThreadExecutor;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/** Selects which audience receives a player's ordinary chat messages. */
public final class ChatCommand implements CommandExecutor, TabCompleter {
    private static final List<String> CHANNELS = List.of("global", "local", "civ");

    private final CivilizationChat chat;
    private final MainThreadExecutor mainThread;

    public ChatCommand(JavaPlugin plugin, CivilizationChat chat) {
        this.chat = chat;
        this.mainThread = new MainThreadExecutor(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can switch chat channels.");
            return true;
        }
        if (args.length != 1) {
            player.sendMessage(ChatColor.RED + "Usage: /chat <global|local|civ>");
            return true;
        }
        ChatChannel channel = ChatChannel.parse(args[0]).orElse(null);
        if (channel == null) {
            player.sendMessage(ChatColor.RED + "Unknown chat channel. Use global, local, or civ.");
            return true;
        }
        chat.select(player, channel).thenAccept(result -> mainThread.run(() -> player.sendMessage(
            (result.success() ? ChatColor.GREEN : ChatColor.RED) + result.message())));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? CommandInput.matching(CHANNELS, args[0]) : List.of();
    }
}
