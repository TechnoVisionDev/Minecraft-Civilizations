package io.github.empireage.civilizations.listener.progression;

import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VillagerTradingListenerTest {
    private final VillagerTradingListener listener = new VillagerTradingListener();

    @Test
    void interactingWithAnyAbstractVillagerIsCancelled() {
        Player player = player();
        PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
        when(event.getRightClicked()).thenReturn(mock(AbstractVillager.class));
        when(event.getPlayer()).thenReturn(player);

        listener.onVillagerInteract(event);

        verify(event).setCancelled(true);
    }

    @Test
    void merchantInventoryPolicyRecognizesMerchantMenus() {
        assertTrue(VillagerTradingListener.merchantInventory(
            "MERCHANT"));
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }
}
