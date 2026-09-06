package io.github.empireage.civilizations.listener.progression;

import io.github.empireage.civilizations.service.progression.DepositService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Objects;

public final class DepositRecoveryListener implements Listener {
    private final DepositService deposits;

    public DepositRecoveryListener(DepositService deposits) {
        this.deposits = Objects.requireNonNull(deposits, "deposits");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (deposits.restorePending(event.getPlayer().getUniqueId())) {
            event.getPlayer().sendMessage("A failed civic deposit was restored to your inventory.");
        }
    }
}
