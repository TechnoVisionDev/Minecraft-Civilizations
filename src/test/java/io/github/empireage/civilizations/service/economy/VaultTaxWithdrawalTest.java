package io.github.empireage.civilizations.service.economy;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VaultTaxWithdrawalTest {
    @Test void insufficientBalanceIsVerifiedWithoutDispatchingWithdrawal() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            FakeEconomy provider = new FakeEconomy(); provider.balance = 4.99;
            var result = port(provider).withdrawTax(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("5.00")).join();
            assertFalse(result.success()); assertTrue(result.verifiedInsufficientFunds());
            assertEquals(0, provider.withdrawals);
        }
    }

    @Test void sufficientBalanceIsChargedExactlyOnceIncludingOfflineOwners() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            FakeEconomy provider = new FakeEconomy(); provider.balance = 5.00;
            VaultEconomyPort port = port(provider);
            UUID operation = UUID.randomUUID(), player = UUID.randomUUID();
            assertTrue(port.withdrawTax(operation, player, new BigDecimal("5.00")).join().success());
            assertTrue(port.withdrawTax(operation, player, new BigDecimal("5.00")).join().success());
            assertEquals(1, provider.withdrawals); assertEquals(0, provider.balance);
        }
    }

    @Test void providerDeclinesAndInvalidBalancesAreNotClassifiedAsInsolvency() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            FakeEconomy provider = new FakeEconomy(); provider.balance = 10; provider.decline = true;
            var declined = port(provider).withdrawTax(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE).join();
            assertFalse(declined.success()); assertFalse(declined.verifiedInsufficientFunds());
            provider.balance = Double.NaN;
            var invalid = port(provider).withdrawTax(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE).join();
            assertFalse(invalid.success()); assertFalse(invalid.verifiedInsufficientFunds());
            provider.failBalance = true;
            var unavailable = port(provider).withdrawTax(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE).join();
            assertFalse(unavailable.success()); assertFalse(unavailable.ambiguous());
            assertFalse(unavailable.verifiedInsufficientFunds());
            assertEquals(1, provider.withdrawals);
        }
    }

    private VaultEconomyPort port(FakeEconomy provider) throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class); Server server = mock(Server.class);
        when(plugin.isEnabled()).thenReturn(true); when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("vault-tax-test"));
        when(server.getOfflinePlayer(any(UUID.class))).thenReturn(mock(OfflinePlayer.class));
        var constructor = VaultEconomyPort.class.getDeclaredConstructor(JavaPlugin.class, Object.class, Class.class);
        constructor.setAccessible(true);
        return constructor.newInstance(plugin, provider, FakeEconomy.class);
    }

    public static final class FakeEconomy {
        public double balance;
        public int withdrawals;
        public boolean decline;
        public boolean failBalance;
        public double getBalance(OfflinePlayer player) {
            if (failBalance) throw new IllegalStateException("Provider unavailable");
            return balance;
        }
        public Response withdrawPlayer(OfflinePlayer player, double amount) {
            withdrawals++;
            if (!decline) balance -= amount;
            return new Response(!decline);
        }
    }
    public static final class Response {
        public final String errorMessage = "INSUFFICIENT_FUNDS: untrusted provider text";
        private final boolean success;
        public Response(boolean success) { this.success = success; }
        public boolean transactionSuccess() { return success; }
    }
}
