package io.github.empireage.civilizations.service.economy;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.config.Settings;
import org.bukkit.plugin.java.JavaPlugin;
import io.github.empireage.civilizations.database.MigrationRunner;
import io.github.empireage.civilizations.database.Sql;
import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.service.territory.EconomyPort;
import io.github.empireage.civilizations.util.UuidBytes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real MySQL SQL/ledger tests with a deterministic economy. Each test rolls back its fixture. */
@EnabledIfSystemProperty(named = "civilizations.mysql.it", matches = "true")
class PlotTaxServiceIntegrationTest {
    private Connection connection;
    private Database database;
    private EconomyService economy;
    private StateCache cache;
    private PlotTaxService taxes;
    private MutableClock clock;
    private UUID owner;
    private UUID leader;
    private UUID world;
    private long civ;
    private BigDecimal balance;
    private String providerMode;
    private final List<UUID> withdrawals = new ArrayList<>();
    private Runnable afterWithdrawal;

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(System.getProperty("civilizations.mysql.url"),
            System.getProperty("civilizations.mysql.user", "root"), System.getProperty("civilizations.mysql.password", ""));
    }

    @BeforeAll static void migrate() throws Exception {
        try (Connection c = connect()) { MigrationRunner.migrate(c, Logger.getLogger("plot-tax-test")); }
    }

    @BeforeEach void setup() throws Exception {
        connection = connect();
        connection.setAutoCommit(false);
        clock = new MutableClock();
        owner = UUID.randomUUID(); leader = UUID.randomUUID(); world = UUID.randomUUID();
        balance = new BigDecimal("100.00"); providerMode = "SUCCESS"; withdrawals.clear(); afterWithdrawal = null;
        update("""
            INSERT INTO civilizations(name, normalized_name, leader_uuid, status, capital_world_uuid, capital_world_name,
                capital_chunk_x, capital_chunk_z, home_x, home_y, home_z, home_yaw, home_pitch)
            VALUES ('TaxTest', ?, ?, 'ACTIVE', ?, 'world', 0, 0, 0, 64, 0, 0, 0)
            """, UUID.randomUUID().toString().substring(0, 24), leader, world);
        civ = number("SELECT LAST_INSERT_ID()");
        update("INSERT INTO civ_members(civ_id, player_uuid, last_known_name, role, joined_at, last_active_at) VALUES (?, ?, 'Leader', 'LEADER', ?, ?)", civ, leader, clock.instant(), clock.instant());
        update("INSERT INTO civ_members(civ_id, player_uuid, last_known_name, role, joined_at, last_active_at) VALUES (?, ?, 'Owner', 'ADVISOR', ?, ?)", civ, owner, clock.instant(), clock.instant());
        database = mock(Database.class); cache = mock(StateCache.class);
        VaultEconomyPort port = mock(VaultEconomyPort.class);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("tax-economy-test"));
        Settings settings = mock(Settings.class);
        when(settings.economyMode()).thenReturn(Settings.EconomyMode.VAULT);
        try (var discovery = mockStatic(VaultEconomyPort.class)) {
            discovery.when(() -> VaultEconomyPort.discover(plugin)).thenReturn(port);
            economy = spy(new EconomyService(plugin, database, settings));
        }
        when(database.healthy()).thenReturn(true); when(cache.ready()).thenReturn(true);
        when(cache.refreshAfterMutation()).thenReturn(CompletableFuture.completedFuture(StateSnapshot.empty()));
        when(database.transaction(any())).thenAnswer(call -> run(call.getArgument(0)));
        when(database.read(any())).thenAnswer(call -> run(call.getArgument(0)));
        when(database.consistentRead(any())).thenAnswer(call -> run(call.getArgument(0)));
        when(port.withdrawTax(any(), any(), any())).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            withdrawals.add(id);
            BigDecimal amount = call.getArgument(2);
            EconomyPort.Result result;
            if (providerMode.equals("AMBIGUOUS")) result = EconomyPort.Result.ambiguous("simulated lost result");
            else if (providerMode.equals("ERROR")) result = EconomyPort.Result.failed("simulated provider outage");
            else if (balance.compareTo(amount) < 0) result = EconomyPort.Result.insufficientFunds();
            else { balance = balance.subtract(amount); result = EconomyPort.Result.ok("paid"); }
            if (afterWithdrawal != null) { Runnable action = afterWithdrawal; afterWithdrawal = null; action.run(); }
            return CompletableFuture.completedFuture(result);
        });
        when(port.refund(any(), any(), any())).thenAnswer(call -> {
            balance = balance.add(call.<BigDecimal>getArgument(2));
            return CompletableFuture.completedFuture(EconomyPort.Result.ok("confirmed"));
        });
        taxes = service();
    }

    private PlotTaxService service() { return new PlotTaxService(database, cache, economy, "test", clock); }

    @AfterEach void cleanup() throws Exception { if (connection != null) { connection.rollback(); connection.close(); } }

    private CompletableFuture<Object> run(io.github.empireage.civilizations.database.SqlFunction<Connection, Object> operation) throws Exception {
        var savepoint = connection.setSavepoint();
        try { return CompletableFuture.completedFuture(operation.apply(connection)); }
        catch (Exception error) { connection.rollback(savepoint); return CompletableFuture.failedFuture(error); }
    }

    private long plot(String type, boolean privateOwner, String listingKind) throws Exception {
        int x = (int) number("SELECT COUNT(*) FROM civ_claims WHERE civ_id = ?", civ) + 1;
        update("""
            INSERT INTO civ_claims(world_uuid, world_name, chunk_x, chunk_z, civ_id, plot_type, plot_owner_uuid,
                listing_kind, listing_seller_uuid, listing_price, acquisition_source, claimed_at, claimed_by,
                purchased_at, purchased_by, home_label, greeting, plot_flags)
            VALUES (?, 'world', ?, 0, ?, ?, ?, ?, ?, 100, 'EXPANSION', ?, ?, ?, ?, 'Home', 'Hello', '{"public_interact":true}')
            """, world, x, civ, type, privateOwner ? owner : null, listingKind, listingKind == null ? null : owner,
            clock.instant(), leader, privateOwner ? clock.instant() : null, privateOwner ? owner : null);
        return number("SELECT LAST_INSERT_ID()");
    }

    private void week() { clock.now = clock.now.plus(PlotTaxService.WEEK); }
    private void tick() { taxes.tick().join(); }
    private void rate(String rate) { assertTrue(taxes.setRate(leader, new BigDecimal(rate)).join().success()); }

    @Test void defaultsToZeroAndNeverChargesPublicPlots() throws Exception {
        long privatePlot = plot("PRIVATE", true, null);
        plot("CIVIC", false, null); plot("CAPITAL", false, null); plot("COMMON", false, null); plot("FOR_SALE", false, "CIVILIZATION");
        assertEquals(new BigDecimal("0.00"), taxes.rate(civ).join());
        tick(); week(); tick();
        assertTrue(withdrawals.isEmpty());
        assertEquals(1, number("SELECT COUNT(*) FROM plot_tax_accounts"));
        assertEquals("PRIVATE", string("SELECT plot_type FROM civ_claims WHERE id = ?", privatePlot));
        assertEquals(0, number("SELECT COUNT(*) FROM plot_tax_bills"));
    }

    @Test void onlyLeaderSetsRateAndChangesGiveAtLeastAWeek() throws Exception {
        long claim = plot("PRIVATE", true, null); tick();
        assertFalse(taxes.setRate(owner, new BigDecimal("5.00")).join().success());
        rate("5.00");
        clock.now = clock.now.plusSeconds(6 * 86400);
        rate("10.00");
        assertEquals(clock.instant().plus(PlotTaxService.WEEK), timestamp("SELECT next_due FROM plot_tax_accounts WHERE claim_id = ?", claim));
        assertEquals(new BigDecimal("10.00"), taxes.overview(owner).join().plots().getFirst().amount());
        tick(); assertTrue(withdrawals.isEmpty());
        week(); tick();
        assertEquals(new BigDecimal("90.00"), balance);
    }

    @Test void chargesEachPlotOnceCreditsTreasuryAndReclaimsOnlyUnaffordablePlots() throws Exception {
        long first = plot("PRIVATE", true, null), second = plot("PRIVATE", true, null), third = plot("PRIVATE", true, null);
        rate("5.00"); tick(); balance = new BigDecimal("10.00"); week(); tick();
        assertEquals(new BigDecimal("0.00"), balance);
        assertEquals(new BigDecimal("10.00"), money("SELECT treasury_balance FROM civilizations WHERE id = ?", civ));
        assertEquals("PRIVATE", string("SELECT plot_type FROM civ_claims WHERE id = ?", first));
        assertEquals("PRIVATE", string("SELECT plot_type FROM civ_claims WHERE id = ?", second));
        assertEquals("CIVIC", string("SELECT plot_type FROM civ_claims WHERE id = ?", third));
        assertEquals(2, number("SELECT COUNT(*) FROM treasury_ledger WHERE reason = 'PLOT_TAX'"));
        assertEquals(3, withdrawals.size());
        taxes = service(); tick();
        assertEquals(3, withdrawals.size(), "restarting must not duplicate settled withdrawals");
        assertEquals(2, number("SELECT COUNT(*) FROM treasury_ledger WHERE reason = 'PLOT_TAX'"));
    }

    @Test void failedResaleTaxClearsPrivatePermissionsListingAndMetadata() throws Exception {
        long claim = plot("FOR_SALE", true, "MEMBER");
        update("INSERT INTO plot_trust(claim_id, trusted_player_uuid, permission_mask, granted_by, created_at) VALUES (?, ?, 7, ?, ?)", claim, leader, owner, clock.instant());
        rate("5"); tick(); week(); balance = BigDecimal.ZERO; tick();
        assertEquals("CIVIC", string("SELECT plot_type FROM civ_claims WHERE id = ?", claim));
        assertEquals(1, number("SELECT COUNT(*) FROM civ_claims WHERE id = ? AND civ_id = ? AND plot_owner_uuid IS NULL AND listing_kind IS NULL AND home_label IS NULL AND plot_flags IS NULL AND purchased_at IS NULL", claim, civ));
        assertEquals(0, number("SELECT COUNT(*) FROM plot_trust WHERE claim_id = ?", claim));
        assertEquals(0, number("SELECT COUNT(*) FROM plot_tax_accounts WHERE claim_id = ?", claim));
        assertEquals(1, number("SELECT COUNT(*) FROM civ_audit_log WHERE action_key = 'PLOT_TAX_RECLAIMED'"));
        assertEquals(0, number("SELECT COUNT(*) FROM treasury_ledger WHERE reason = 'PLOT_TAX'"));
    }

    @Test void providerErrorsNeverReclaimAndCanRetrySafely() throws Exception {
        long claim = plot("PRIVATE", true, null); rate("5"); tick(); week(); providerMode = "ERROR"; tick();
        assertEquals("PRIVATE", string("SELECT plot_type FROM civ_claims WHERE id = ?", claim));
        assertEquals(1, withdrawals.size());
        providerMode = "SUCCESS"; tick();
        assertEquals(2, withdrawals.size());
        assertEquals(withdrawals.get(0), withdrawals.get(1), "retry retains the same persisted operation");
        assertEquals(new BigDecimal("95.00"), balance);
    }

    @Test void ambiguousPaymentsRemainProtectedAcrossRestartWithoutRetry() throws Exception {
        long claim = plot("PRIVATE", true, null); rate("5"); tick(); week(); providerMode = "AMBIGUOUS"; tick();
        taxes = service(); tick(); week(); tick();
        assertEquals(1, withdrawals.size());
        assertEquals("PRIVATE", string("SELECT plot_type FROM civ_claims WHERE id = ?", claim));
        assertEquals("WITHDRAWAL_IN_FLIGHT", taxes.overview(owner).join().plots().getFirst().paymentState());
        assertEquals(1, number("SELECT COUNT(*) FROM plot_tax_bills"));
    }

    @Test void ownershipChangeDuringWithdrawalRefundsFormerOwner() throws Exception {
        long claim = plot("PRIVATE", true, null); rate("5"); tick(); week();
        UUID buyer = UUID.randomUUID();
        afterWithdrawal = () -> {
            try {
                update("UPDATE civ_claims SET plot_owner_uuid = ?, purchased_at = ? WHERE id = ?", buyer, clock.instant(), claim);
                PlotTaxService.startOwnership(connection, claim, civ, buyer, clock.instant(), clock.instant(), new BigDecimal("5.00"));
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        tick();
        assertEquals(new BigDecimal("100.00"), balance);
        assertEquals("CANCELLED", string("SELECT status FROM plot_tax_bills"));
        assertEquals(0, number("SELECT COUNT(*) FROM treasury_ledger WHERE reason = 'PLOT_TAX'"));
        assertEquals("PRIVATE", string("SELECT plot_type FROM civ_claims WHERE id = ?", claim));
        tick(); verify(economy, times(1)).refundCharge(any(), any());
    }

    @Test void sameOwnerRepurchasingDoesNotInheritOldTaxReclamation() throws Exception {
        long claim = plot("PRIVATE", true, null); rate("5"); tick(); week(); balance = BigDecimal.ZERO;
        afterWithdrawal = () -> {
            try {
                update("UPDATE civ_claims SET purchased_at = ? WHERE id = ?", clock.instant(), claim);
                PlotTaxService.startOwnership(connection, claim, civ, owner, clock.instant(), clock.instant(), new BigDecimal("5.00"));
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        tick();
        assertEquals("PRIVATE", string("SELECT plot_type FROM civ_claims WHERE id = ?", claim));
        assertEquals("CANCELLED", string("SELECT status FROM plot_tax_bills"));
    }

    @Test void claimDeletedDuringPaymentStillHasARecoverableRefund() throws Exception {
        long claim = plot("PRIVATE", true, null); rate("5"); tick(); week();
        afterWithdrawal = () -> {
            try { update("DELETE FROM civ_claims WHERE id = ?", claim); } catch (Exception e) { throw new RuntimeException(e); }
        };
        tick();
        assertEquals(new BigDecimal("100.00"), balance);
        assertEquals("CANCELLED", string("SELECT status FROM plot_tax_bills"));
    }

    @Test void alreadyAppliedPaymentFinalizesExactlyOnceAfterRestart() throws Exception {
        long claim = plot("PRIVATE", true, null); rate("5"); tick(); week();
        UUID id = taxes.assess(connection, claim);
        assertEquals(id, taxes.assess(connection, claim));
        update("UPDATE economy_operations SET state = 'EXTERNAL_APPLIED' WHERE operation_id = ?", id);
        taxes = service(); tick(); tick();
        assertTrue(withdrawals.isEmpty());
        assertEquals(new BigDecimal("5.00"), money("SELECT treasury_balance FROM civilizations WHERE id = ?", civ));
        assertEquals(1, number("SELECT COUNT(*) FROM treasury_ledger WHERE reason = 'PLOT_TAX'"));
        assertEquals("COMPLETED", string("SELECT state FROM economy_operations WHERE operation_id = ?", id));
    }

    @Test void zeroingRateAndUnavailableEconomyNeverReclaim() throws Exception {
        long claim = plot("PRIVATE", true, null); rate("5"); tick(); rate("0"); week(); tick();
        assertTrue(withdrawals.isEmpty());
        doReturn(false).when(economy).enabled();
        assertFalse(taxes.setRate(leader, new BigDecimal("5")).join().success());
        week(); tick();
        assertEquals("PRIVATE", string("SELECT plot_type FROM civ_claims WHERE id = ?", claim));
    }

    @Test void missedScansCollectOnceThenGiveAFullWeekAndKeepOfflineNotices() throws Exception {
        long claim = plot("PRIVATE", true, null); rate("5"); tick(); clock.now = clock.now.plusSeconds(30 * 86400L); tick(); tick();
        assertEquals(1, withdrawals.size());
        assertEquals(clock.instant().plus(PlotTaxService.WEEK), timestamp("SELECT next_due FROM plot_tax_accounts WHERE claim_id = ?", claim));
        var notices = taxes.notices(owner).join();
        assertEquals(1, notices.size()); assertEquals("PAID", notices.getFirst().status());
        taxes.acknowledge(leader, notices).join(); assertEquals(1, taxes.notices(owner).join().size());
        taxes.acknowledge(owner, notices).join(); assertTrue(taxes.notices(owner).join().isEmpty());
    }

    @Test void activePurchaseDefersAssessmentAndTaxBlocksOtherTransfers() throws Exception {
        long claim = plot("FOR_SALE", true, "MEMBER"); rate("5"); tick(); week();
        assertTrue(PlotTaxService.hasUnsettledTax(connection, claim, clock.instant()));
        UUID purchase = UUID.randomUUID();
        update("INSERT INTO economy_operations(operation_id, operation_type, player_uuid, civ_id, claim_id, amount, state, created_at, updated_at) VALUES (?, 'PLOT_PURCHASE', ?, ?, ?, 100, 'PENDING', ?, ?)", purchase, leader, civ, claim, clock.instant(), clock.instant());
        tick(); assertTrue(withdrawals.isEmpty());
        update("UPDATE economy_operations SET state = 'FAILED' WHERE operation_id = ?", purchase);
        tick(); assertEquals(1, withdrawals.size());
        assertFalse(PlotTaxService.hasUnsettledTax(connection, claim, clock.instant()));
    }

    private void update(String sql, Object... values) throws Exception {
        try (var statement = Sql.prepare(connection, sql, values)) { statement.executeUpdate(); }
    }
    private long number(String sql, Object... values) throws Exception {
        try (var statement = Sql.prepare(connection, sql, values); var result = statement.executeQuery()) { assertTrue(result.next()); return result.getLong(1); }
    }
    private String string(String sql, Object... values) throws Exception {
        try (var statement = Sql.prepare(connection, sql, values); var result = statement.executeQuery()) { assertTrue(result.next()); return result.getString(1); }
    }
    private BigDecimal money(String sql, Object... values) throws Exception {
        try (var statement = Sql.prepare(connection, sql, values); var result = statement.executeQuery()) { assertTrue(result.next()); return result.getBigDecimal(1); }
    }
    private Instant timestamp(String sql, Object... values) throws Exception {
        try (var statement = Sql.prepare(connection, sql, values); var result = statement.executeQuery()) { assertTrue(result.next()); return result.getTimestamp(1).toInstant(); }
    }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-04T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
