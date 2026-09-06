package io.github.empireage.civilizations.runtime;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TutorialBookServiceTest {
    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final PersistentDataContainer data = mock(PersistentDataContainer.class);
    private final Map<NamespacedKey, Object> saved = new HashMap<>();
    private final ItemStack book = mock(ItemStack.class);
    private final Instant now = Instant.parse("2026-09-06T12:00:00Z");

    @BeforeEach void setup() {
        when(plugin.getName()).thenReturn("Civilizations");
        when(player.getInventory()).thenReturn(inventory);
        when(player.getPersistentDataContainer()).thenReturn(data);
        when(inventory.addItem(book)).thenReturn(new HashMap<>());
        when(data.get(any(), any())).thenAnswer(call -> saved.get(call.getArgument(0)));
        doAnswer(call -> { saved.put(call.getArgument(0), call.getArgument(2)); return null; })
            .when(data).set(any(), any(), any());
        doAnswer(call -> { saved.remove(call.getArgument(0)); return null; }).when(data).remove(any());
    }

    private TutorialBookService service(Instant time) {
        TutorialBookService service = spy(new TutorialBookService(plugin, Clock.fixed(time, ZoneOffset.UTC)));
        doReturn(book).when(service).createBook();
        return service;
    }

    private void join(TutorialBookService service) {
        service.onJoin(new PlayerJoinEvent(player, "joined"));
    }

    @Test void firstJoinGivesOneBookAndStartsCooldown() {
        TutorialBookService service = service(now);
        join(service);
        join(service); // Even a repeated event before hasPlayedBefore changes cannot duplicate delivery.
        assertFalse(service.give(player).success());
        verify(inventory, times(1)).addItem(book);
        verify(player).saveData();
    }

    @Test void returningPlayerIsNotGivenAnAutomaticBookButCanRequestOne() {
        when(player.hasPlayedBefore()).thenReturn(true);
        TutorialBookService service = service(now);
        join(service);
        verifyNoInteractions(inventory);
        assertTrue(service.give(player).success());
    }

    @Test void savedCooldownSurvivesServiceRestartAndExpiresAtExactly24Hours() {
        assertTrue(service(now).give(player).success());
        var early = service(now.plusSeconds(86_399)).give(player);
        assertFalse(early.success());
        assertTrue(early.message().contains("0h 0m 1s"));
        assertTrue(service(now.plusSeconds(86_400)).give(player).success());
        assertFalse(service(now.plusSeconds(86_401)).give(player).success());
        verify(inventory, times(2)).addItem(book);
    }

    @Test void reconnectAfterCooldownDoesNotGiveAnotherAutomaticBook() {
        join(service(now));
        when(player.hasPlayedBefore()).thenReturn(true);
        join(service(now.plusSeconds(172_800)));
        verify(inventory, times(1)).addItem(book);
    }

    @Test void fullInventoryDoesNotStartCooldownAndAllowsImmediateRetry() {
        when(inventory.addItem(book)).thenReturn(new HashMap<>(Map.of(0, book)));
        TutorialBookService service = service(now);
        assertFalse(service.give(player).success());
        assertTrue(saved.isEmpty());
        verify(player, never()).saveData();
        when(inventory.addItem(book)).thenReturn(new HashMap<>());
        assertTrue(service.give(player).success());
    }

    @Test void pendingWelcomeBookSurvivesRestartAndRetriesOnJoin() {
        when(inventory.addItem(book)).thenReturn(new HashMap<>(Map.of(0, book)));
        join(service(now));
        verify(player).saveData();
        when(player.hasPlayedBefore()).thenReturn(true);
        when(inventory.addItem(book)).thenReturn(new HashMap<>());
        join(service(now.plusSeconds(60)));
        assertFalse(saved.containsKey(new NamespacedKey(plugin, "tutorial-initial-pending")));
        verify(inventory, times(2)).addItem(book);
        verify(player, times(2)).saveData();
    }

    @Test void commandCanCollectPendingWelcomeBookWithoutAnotherCopyOnJoin() {
        when(inventory.addItem(book)).thenReturn(new HashMap<>(Map.of(0, book)));
        join(service(now));
        when(inventory.addItem(book)).thenReturn(new HashMap<>());
        assertTrue(service(now).give(player).success());
        when(player.hasPlayedBefore()).thenReturn(true);
        join(service(now.plusSeconds(172_800)));
        verify(inventory, times(2)).addItem(book);
    }

    @Test void deadPlayerCannotConsumeDeliveryOrStartCooldown() {
        when(player.isDead()).thenReturn(true);
        assertFalse(service(now).give(player).success());
        verifyNoInteractions(inventory);
        assertTrue(saved.isEmpty());
    }

    @Test void differentPlayersHaveIndependentCooldowns() {
        assertTrue(service(now).give(player).success());
        Player another = mock(Player.class);
        when(another.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(another.getInventory()).thenReturn(inventory);
        assertTrue(service(now).give(another).success());
        verify(inventory, times(2)).addItem(book);
    }

    @Test void createsWrittenBookWithReadableTutorialPages() {
        BookMeta meta = mock(BookMeta.class);
        try (var stacks = mockConstruction(ItemStack.class, (stack, context) -> {
            assertEquals(Material.WRITTEN_BOOK, context.arguments().getFirst());
            when(stack.getItemMeta()).thenReturn(meta);
        })) {
            ItemStack result = new TutorialBookService(plugin).createBook();
            assertEquals(stacks.constructed().getFirst(), result);
            verify(meta).setTitle("Civilizations Tutorial");
            verify(meta).setAuthor("Civilizations");
            verify(result).setItemMeta(meta);
            @SuppressWarnings("unchecked") ArgumentCaptor<List<String>> pages = ArgumentCaptor.forClass(List.class);
            verify(meta).setPages(pages.capture());
            assertEquals(15, pages.getValue().size());
            for (String page : pages.getValue()) {
                String plain = ChatColor.stripColor(page);
                assertTrue(plain.lines().count() <= 14, plain);
                // Budget for the default Minecraft book font; bold headings add a pixel per glyph.
                String[] lines = plain.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    int width = 0;
                    for (char c : lines[i].toCharArray()) {
                        width += switch (c) {
                            case 'i', '!', '.', ',', ':', ';', '\'' -> 2;
                            case 'l' -> 3;
                            case ' ', 't', 'I', '[', ']' -> 4;
                            case 'f', 'k', '<', '>' -> 5;
                            default -> 6;
                        };
                        if (i == 0 && c != ' ') width++;
                    }
                    assertTrue(width <= 114, "Book line is too wide (" + width + "px): " + lines[i]);
                }
            }
            assertTrue(pages.getValue().getLast().contains("/civ tutorial"));
        }
    }
}
