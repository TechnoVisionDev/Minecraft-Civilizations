package io.github.empireage.civilizations.command;

import org.junit.jupiter.api.Test;
import io.github.empireage.civilizations.config.ReligionCatalog;
import io.github.empireage.civilizations.service.religion.ReligionService;
import io.github.empireage.civilizations.testutil.BukkitItemMocks;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.*;

class ReligionCommandTest {
    @Test
    void pantheonUsesEachGodsSavedFavorEvenAfterCooldownExpires() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        ReligionService service = mock(ReligionService.class);
        when(service.progress(playerId)).thenReturn(CompletableFuture.completedFuture(Map.of(
            "zeus", new ReligionService.GodProgress(3, Instant.now().minusSeconds(60)),
            "athena", new ReligionService.GodProgress(2, Instant.now().plusSeconds(3600)))));
        Inventory menu = mock(Inventory.class);
        Map<Integer, ItemStack> icons = new HashMap<>();
        doAnswer(call -> { icons.put(call.getArgument(0), call.getArgument(1)); return null; })
            .when(menu).setItem(anyInt(), any());
        Command command = mock(Command.class);
        when(command.getName()).thenReturn("religion");
        try (var bukkit = mockStatic(Bukkit.class); var items = new BukkitItemMocks()) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(27), eq("The Greek Pantheon")))
                .thenReturn(menu);
            new ReligionCommand(plugin, ReligionCatalog.load(new File("src/main/resources/religion.yml")), service)
                .onCommand(player, command, "religion", new String[0]);
            verify(player).openInventory(menu);
            assertEquals(6, icons.size());
            for (ItemStack icon : icons.values()) {
                String name = ChatColor.stripColor(icon.getItemMeta().getDisplayName());
                String lore = ChatColor.stripColor(String.join("\n", icon.getItemMeta().getLore()));
                if (name.equals("Zeus")) {
                    assertTrue(lore.contains("Level 3/3"));
                    assertTrue(lore.contains("roll of 1-20"));
                    assertTrue(lore.contains("Available — click to choose"));
                } else if (name.equals("Athena")) {
                    assertTrue(lore.contains("Level 2/3"));
                    assertTrue(lore.contains("roll of 1-25"));
                    assertFalse(lore.contains("click to choose"));
                } else {
                    assertTrue(lore.contains("Level 1/3"));
                    assertTrue(lore.contains("roll of 1-30"));
                }
            }
        }
    }

    @Test
    void menuExplainsTheCurrentAndNextTrialForEveryFavorLevel() {
        int[] maxima = {30, 25, 20};
        for (int level = 1; level <= 3; level++) {
            String lore = String.join("\n", ReligionCommand.favorLore(level));
            assertTrue(lore.contains("Level " + level + "/3"));
            assertTrue(lore.contains("exceed a roll of 1-" + maxima[level - 1]));
            assertTrue(lore.contains("entire held stack is consumed"));
            if (level < 3) {
                assertTrue(lore.contains("Next success: level " + (level + 1) + " (rolls 1-" + maxima[level] + ")"));
            } else {
                assertTrue(lore.contains("Maximum favor reached"));
                assertFalse(lore.contains("Next success"));
            }
        }
    }
}
