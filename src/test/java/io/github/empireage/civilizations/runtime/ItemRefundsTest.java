package io.github.empireage.civilizations.runtime;

import io.github.empireage.civilizations.cache.*;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.domain.*;
import io.github.empireage.civilizations.service.progression.*;
import io.github.empireage.civilizations.service.economy.EconomyService;
import io.github.empireage.civilizations.service.lifecycle.CivilizationLifecycleService;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItemRefundsTest {
    @TempDir Path directory;
    private final UUID playerId = UUID.randomUUID();
    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final PersistentDataContainer pdc = mock(PersistentDataContainer.class);
    private final Map<NamespacedKey, Byte> receipts = new HashMap<>();
    private final AtomicReference<ItemStack[]> contents = new AtomicReference<>(new ItemStack[2]);
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<ItemStack> itemSerialization;

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        // Exercise the actual YAML and disk journal with a small, server-free item codec.
        itemSerialization = mockStatic(ItemStack.class);
        itemSerialization.when(() -> ItemStack.deserialize(anyMap())).thenAnswer(call -> {
            Map<String, Object> values = call.getArgument(0);
            return stack(Material.valueOf((String) values.get("type")), ((Number) values.get("amount")).intValue());
        });
        when(plugin.getName()).thenReturn("Civilizations");
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getPersistentDataContainer()).thenReturn(pdc);
        when(inventory.getStorageContents()).thenAnswer(call -> contents.get().clone());
        doAnswer(call -> { contents.set(((ItemStack[]) call.getArgument(0)).clone()); return null; })
            .when(inventory).setStorageContents(any());
        when(pdc.get(any(), eq(PersistentDataType.BYTE))).thenAnswer(call -> receipts.get(call.getArgument(0)));
        doAnswer(call -> { receipts.put(call.getArgument(0), call.getArgument(2)); return null; })
            .when(pdc).set(any(), eq(PersistentDataType.BYTE), anyByte());
        when(pdc.getKeys()).thenAnswer(call -> Set.copyOf(receipts.keySet()));
        doAnswer(call -> { receipts.remove(call.getArgument(0)); return null; }).when(pdc).remove(any());
    }

    @AfterEach void cleanup() {
        itemSerialization.close();
        bukkit.close();
    }

    private static ItemStack stack(Material type, int count) {
        ItemStack stack = mock(ItemStack.class);
        AtomicInteger amount = new AtomicInteger(count);
        when(stack.getType()).thenReturn(type);
        when(stack.getAmount()).thenAnswer(call -> amount.get());
        when(stack.getMaxStackSize()).thenReturn(64);
        doAnswer(call -> { amount.set(call.getArgument(0)); return null; }).when(stack).setAmount(anyInt());
        when(stack.clone()).thenAnswer(call -> stack(type, amount.get()));
        when(stack.isSimilar(any())).thenAnswer(call -> ((ItemStack) call.getArgument(0)).getType() == type);
        when(stack.serialize()).thenAnswer(call -> Map.of("type", type.name(), "amount", amount.get()));
        return stack;
    }

    private ItemRefunds.Ticket debit(ItemRefunds refunds) {
        var ticket = refunds.prepare(UUID.randomUUID(), playerId, List.of(stack(Material.STONE, 32)));
        refunds.debit(player, ticket, new ItemStack[2]);
        return ticket;
    }

    @Test void offlineRefundSurvivesRestartAndIsDeliveredOnce() {
        for (String scope : List.of("deposits", "founding")) {
            contents.set(new ItemStack[2]);
            ItemRefunds before = new ItemRefunds(plugin, scope);
            var ticket = debit(before);
            when(player.isOnline()).thenReturn(false);
            before.refundable(ticket);
            assertFalse(before.restore(player));
            ItemRefunds after = new ItemRefunds(plugin, scope);
            assertTrue(after.hasPending(playerId));
            when(player.isOnline()).thenReturn(true);
            assertTrue(after.restore(player));
            assertEquals(32, contents.get()[0].getAmount());
            assertFalse(new ItemRefunds(plugin, scope).restore(player));
            assertEquals(32, contents.get()[0].getAmount());
            assertFalse(after.hasPending(playerId));
        }
    }

    @Test void crashAfterSavingRefundDoesNotDeliverItAgain() {
        ItemRefunds refunds = new ItemRefunds(plugin, "deposits");
        refunds.refundable(debit(refunds));
        // Inventory and receipt have been saved; the process dies before journal deletion.
        doThrow(new SimulatedCrash()).when(player).saveData();
        assertThrows(SimulatedCrash.class, () -> refunds.restore(player));
        assertEquals(32, contents.get()[0].getAmount());
        assertTrue(refunds.hasPending(playerId));
        doNothing().when(player).saveData();
        assertFalse(new ItemRefunds(plugin, "deposits").restore(player));
        assertEquals(32, contents.get()[0].getAmount());
        assertFalse(refunds.hasPending(playerId));
    }

    @Test void uncertainDatabaseOutcomeIsNeverAutomaticallyRefunded() {
        ItemRefunds refunds = new ItemRefunds(plugin, "deposits");
        debit(refunds);
        assertFalse(new ItemRefunds(plugin, "deposits").restore(player));
        assertNull(contents.get()[0]);
    }

    @Test void missingDebitReceiptDoesNotDuplicateOriginalInventory() {
        ItemRefunds refunds = new ItemRefunds(plugin, "deposits");
        var ticket = refunds.prepare(UUID.randomUUID(), playerId, List.of(stack(Material.STONE, 32)));
        contents.set(new ItemStack[]{stack(Material.STONE, 32), null});
        refunds.refundable(ticket);
        assertFalse(refunds.restore(player));
        assertEquals(32, contents.get()[0].getAmount());
    }

    @Test void fullInventoryKeepsWholeRefundPendingUntilSpaceIsAvailable() {
        ItemRefunds refunds = new ItemRefunds(plugin, "deposits");
        refunds.refundable(debit(refunds));
        contents.set(new ItemStack[]{stack(Material.STONE, 60), stack(Material.DIRT, 64)});
        assertFalse(refunds.restore(player));
        assertEquals(60, contents.get()[0].getAmount());
        assertTrue(refunds.hasPending(playerId));
        contents.set(new ItemStack[]{stack(Material.STONE, 60), null});
        assertTrue(refunds.restore(player));
        assertEquals(64, contents.get()[0].getAmount());
        assertEquals(28, contents.get()[1].getAmount());
    }

    @Test void journalFailureHappensBeforeAnyPlayerDebitIsSaved() throws Exception {
        Files.writeString(directory.resolve("item-refunds"), "not a directory");
        assertThrows(IllegalStateException.class, () -> debit(new ItemRefunds(plugin, "deposits")));
        verify(player, never()).saveData();
        assertTrue(receipts.isEmpty());
    }

    @Test void failedPlayerSaveLeavesRefundAvailableForRetry() {
        ItemRefunds refunds = new ItemRefunds(plugin, "deposits");
        refunds.refundable(debit(refunds));
        doThrow(new IllegalStateException("save failed")).when(player).saveData();
        assertThrows(IllegalStateException.class, () -> refunds.restore(player));
        assertNull(contents.get()[0]);
        assertTrue(refunds.hasPending(playerId));
        doNothing().when(player).saveData();
        assertTrue(refunds.restore(player));
        assertEquals(32, contents.get()[0].getAmount());
    }

    @Test void debitSaveFailureRestoresInventoryBeforeDatabaseWorkCanStart() {
        ItemRefunds refunds = new ItemRefunds(plugin, "deposits");
        contents.set(new ItemStack[]{stack(Material.STONE, 32), null});
        doThrow(new IllegalStateException("first save failed")).doNothing().when(player).saveData();
        assertThrows(IllegalStateException.class, () -> debit(refunds));
        assertEquals(32, contents.get()[0].getAmount());
        assertTrue(receipts.isEmpty());
    }

    @Test void depositRollbackWhileOfflineIsRecoveredByNewServiceInstance() {
        Database database = mock(Database.class);
        StateCache cache = mock(StateCache.class);
        StateSnapshot snapshot = mock(StateSnapshot.class);
        Member member = mock(Member.class);
        when(member.civilizationId()).thenReturn(1L);
        when(cache.snapshot()).thenReturn(snapshot);
        when(snapshot.member(playerId)).thenReturn(member);
        CivicItemService items = mock(CivicItemService.class);
        StockpileService stockpile = mock(StockpileService.class);
        when(items.identify(any())).thenReturn(Optional.of(new ResourceKey("masonry", 1)));
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        CompletableFuture<Object> commit = new CompletableFuture<>();
        when(database.transaction(any())).thenAnswer(call -> commit);
        when(database.read(any())).thenAnswer(call -> CompletableFuture.completedFuture(false));
        contents.set(new ItemStack[]{stack(Material.STONE, 32), null});
        DepositService before = new DepositService(plugin, database, cache, items, stockpile, "test");
        var result = before.deposit(player, DepositService.Scope.HAND);
        assertNull(contents.get()[0]);
        when(player.isOnline()).thenReturn(false);
        commit.completeExceptionally(new IllegalStateException("rolled back"));
        assertFalse(result.join().success());
        before.close();
        DepositService after = new DepositService(plugin, database, cache, items, stockpile, "test");
        when(player.isOnline()).thenReturn(true);
        bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(player);
        assertTrue(after.restorePending(playerId));
        assertEquals(32, contents.get()[0].getAmount());
        assertFalse(after.restorePending(playerId));
    }

    @Test void foundingLoginListenerDeliversPersistedRefundAfterRestart() {
        ItemRefunds refunds = new ItemRefunds(plugin, "founding");
        refunds.refundable(debit(refunds));
        FoundingCoordinator restarted = new FoundingCoordinator(plugin, mock(Settings.class),
            mock(CivicItemService.class), mock(CivilizationLifecycleService.class), mock(EconomyService.class));
        restarted.onJoin(new PlayerJoinEvent(player, "joined"));
        assertEquals(32, contents.get()[0].getAmount());
        restarted.onJoin(new PlayerJoinEvent(player, "joined again"));
        assertEquals(32, contents.get()[0].getAmount());
        assertFalse(refunds.hasPending(playerId));
    }

    private static final class SimulatedCrash extends Error {}
}
