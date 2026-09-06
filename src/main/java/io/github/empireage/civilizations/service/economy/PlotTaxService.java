package io.github.empireage.civilizations.service.economy;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.database.AuditLog;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.database.JsonData;
import io.github.empireage.civilizations.database.Sql;
import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.domain.PlotType;
import io.github.empireage.civilizations.service.territory.EconomyPort;
import io.github.empireage.civilizations.util.UuidBytes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Weekly private-plot taxes. Each bill and payment intent commits before any external withdrawal. */
public final class PlotTaxService implements AutoCloseable {
    public static final Duration WEEK = Duration.ofDays(7);
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private final Database database;
    private final StateCache cache;
    private final EconomyService economy;
    private final String serverId;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean closed;

    public PlotTaxService(Database database, StateCache cache, EconomyService economy, String serverId) {
        this(database, cache, economy, serverId, Clock.systemUTC());
    }

    PlotTaxService(Database database, StateCache cache, EconomyService economy, String serverId, Clock clock) {
        this.database = database;
        this.cache = cache;
        this.economy = economy;
        this.serverId = serverId;
        this.clock = clock;
    }

    public boolean enabled() { return !closed && economy.enabled(); }

    public static boolean taxable(PlotType type, UUID owner, String listingKind) {
        return owner != null && (type == PlotType.PRIVATE || (type == PlotType.FOR_SALE && "MEMBER".equals(listingKind)));
    }

    public static BigDecimal normalizeRate(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        BigDecimal result = amount.setScale(2, RoundingMode.UNNECESSARY);
        if (result.signum() < 0 || result.compareTo(new BigDecimal("1000000000.00")) > 0)
            throw new IllegalArgumentException("Weekly tax must be between 0 and 1,000,000,000 with at most two decimal places.");
        return result;
    }

    public CompletableFuture<BigDecimal> rate(long civilizationId) {
        return database.read(connection -> rate(connection, civilizationId));
    }

    public static BigDecimal rate(Connection connection, long civilizationId) throws Exception {
        try (var statement = Sql.prepare(connection, "SELECT weekly_amount FROM civ_plot_taxes WHERE civ_id = ?", civilizationId);
             var result = statement.executeQuery()) {
            return result.next() ? result.getBigDecimal(1) : ZERO;
        }
    }

    /** Called in the purchase transaction. A new owner always gets a full week before the first charge. */
    public static void startOwnership(Connection connection, long claimId, long civilizationId, UUID owner,
                                      Instant acquiredAt, Instant now, BigDecimal amount) throws Exception {
        try (var statement = Sql.prepare(connection, """
            INSERT INTO plot_tax_accounts(claim_id, tenure_id, civ_id, owner_uuid, acquired_at, weekly_amount, next_due)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE tenure_id = VALUES(tenure_id), civ_id = VALUES(civ_id),
                owner_uuid = VALUES(owner_uuid), acquired_at = VALUES(acquired_at),
                weekly_amount = VALUES(weekly_amount), next_due = VALUES(next_due)
            """, claimId, UUID.randomUUID(), civilizationId, owner, acquiredAt, amount, now.plus(WEEK))) {
            statement.executeUpdate();
        }
    }

    /** Ownership transfers wait for a due or unresolved tax bill so its outcome cannot race the buyer. */
    public static boolean hasUnsettledTax(Connection connection, long claimId, Instant now) throws Exception {
        try (var statement = Sql.prepare(connection, """
            SELECT 1 FROM plot_tax_accounts a JOIN civ_claims c ON c.id = a.claim_id
            WHERE a.claim_id = ? AND a.owner_uuid = c.plot_owner_uuid AND a.civ_id = c.civ_id
              AND a.acquired_at = c.purchased_at AND (
                (a.weekly_amount > 0 AND a.next_due <= ?) OR EXISTS (
                    SELECT 1 FROM plot_tax_bills b WHERE b.tenure_id = a.tenure_id AND b.status IN ('PENDING','REFUND_PENDING')))
            """, claimId, now); var result = statement.executeQuery()) { return result.next(); }
    }

    public CompletableFuture<OperationResult> setRate(UUID actor, BigDecimal amount) {
        BigDecimal normalized = normalizeRate(amount);
        if (!enabled() && normalized.signum() > 0)
            return CompletableFuture.completedFuture(OperationResult.denied("Weekly taxes require an available Vault economy."));
        return database.transaction(connection -> {
            // Membership is authoritative; advisors cannot change the tax rate.
            long civ;
            try (var statement = Sql.prepare(connection, "SELECT civ_id FROM civ_members WHERE player_uuid = ?", actor);
                 var result = statement.executeQuery()) {
                if (!result.next()) return OperationResult.denied("You do not belong to a civilization.");
                civ = result.getLong(1);
            }
            try (var statement = Sql.prepare(connection,
                "SELECT leader_uuid FROM civilizations WHERE id = ? AND status = 'ACTIVE' FOR UPDATE", civ);
                 var result = statement.executeQuery()) {
                if (!result.next() || !actor.equals(UuidBytes.get(result, "leader_uuid")))
                    return OperationResult.denied("Only the civilization leader can set weekly plot taxes.");
            }
            BigDecimal previous = rate(connection, civ);
            if (previous.compareTo(normalized) == 0) return OperationResult.ok("Weekly tax is already $" + normalized + " per private plot.");
            Instant now = clock.instant();
            try (var statement = Sql.prepare(connection, """
                INSERT INTO civ_plot_taxes(civ_id, weekly_amount, changed_at) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE weekly_amount = VALUES(weekly_amount), changed_at = VALUES(changed_at)
                """, civ, normalized, now)) { statement.executeUpdate(); }
            // Announce the exact upcoming rate. Changes never bring a charge closer than seven days.
            try (var statement = Sql.prepare(connection, """
                UPDATE plot_tax_accounts SET weekly_amount = ?, next_due = GREATEST(next_due, ?)
                WHERE civ_id = ? AND NOT EXISTS (
                    SELECT 1 FROM plot_tax_bills b WHERE b.tenure_id = plot_tax_accounts.tenure_id
                      AND b.status IN ('PENDING','REFUND_PENDING'))
                """, normalized, now.plus(WEEK), civ)) { statement.executeUpdate(); }
            AuditLog.write(connection, civ, actor, "PLOT_TAX_RATE_CHANGED", "CIVILIZATION", Long.toString(civ),
                Map.of("previous", previous.toPlainString(), "weekly_amount", normalized.toPlainString()), serverId);
            return OperationResult.ok("Weekly plot tax set to $" + normalized
                + ". Upcoming charges use this rate after at least seven days; already-issued bills keep their rate.");
        });
    }

    public CompletableFuture<TaxOverview> overview(UUID player) {
        return database.consistentRead(connection -> {
            long civ;
            boolean leader;
            try (var statement = Sql.prepare(connection, """
                SELECT m.civ_id, c.leader_uuid FROM civ_members m JOIN civilizations c ON c.id = m.civ_id
                WHERE m.player_uuid = ? AND c.status = 'ACTIVE'
                """, player); var result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("You do not belong to a civilization.");
                civ = result.getLong("civ_id");
                leader = player.equals(UuidBytes.get(result, "leader_uuid"));
            }
            BigDecimal rate = rate(connection, civ);
            List<PlotTaxView> plots = new ArrayList<>();
            try (var statement = Sql.prepare(connection, """
                SELECT c.id, c.world_name, c.chunk_x, c.chunk_z, c.home_label, a.next_due,
                    a.weekly_amount, b.amount AS pending_amount, e.state AS payment_state
                FROM civ_claims c LEFT JOIN plot_tax_accounts a ON a.claim_id = c.id
                    AND a.owner_uuid = c.plot_owner_uuid AND a.civ_id = c.civ_id AND a.acquired_at = c.purchased_at
                LEFT JOIN plot_tax_bills b ON b.tenure_id = a.tenure_id AND b.status IN ('PENDING','REFUND_PENDING')
                LEFT JOIN economy_operations e ON e.operation_id = b.operation_id
                WHERE c.civ_id = ? AND c.plot_owner_uuid = ?
                  AND (c.plot_type = 'PRIVATE' OR (c.plot_type = 'FOR_SALE' AND c.listing_kind = 'MEMBER'))
                ORDER BY c.id
                """, civ, player); var result = statement.executeQuery()) {
                while (result.next()) {
                    var due = result.getTimestamp("next_due");
                    BigDecimal scheduled = result.getBigDecimal("weekly_amount");
                    BigDecimal pending = result.getBigDecimal("pending_amount");
                    plots.add(new PlotTaxView(result.getLong("id"), result.getString("world_name"),
                        result.getInt("chunk_x"), result.getInt("chunk_z"), result.getString("home_label"),
                        pending != null ? pending : scheduled == null ? rate : scheduled,
                        due == null ? null : due.toInstant(), result.getString("payment_state")));
                }
            }
            return new TaxOverview(civ, rate, leader, List.copyOf(plots), enabled());
        });
    }

    /** Bounded, non-overlapping scan; offline players are billed through Vault's OfflinePlayer account API. */
    public CompletableFuture<Void> tick() {
        if (!enabled() || !database.healthy() || !cache.ready() || !running.compareAndSet(false, true))
            return CompletableFuture.completedFuture(null);
        return database.read(connection -> {
            List<Long> ids = new ArrayList<>();
            try (var statement = Sql.prepare(connection, """
                SELECT c.id FROM civ_claims c JOIN civilizations v ON v.id = c.civ_id
                LEFT JOIN plot_tax_accounts a ON a.claim_id = c.id
                WHERE v.status = 'ACTIVE' AND c.plot_owner_uuid IS NOT NULL AND c.purchased_at IS NOT NULL
                  AND (c.plot_type = 'PRIVATE' OR (c.plot_type = 'FOR_SALE' AND c.listing_kind = 'MEMBER'))
                  AND (a.claim_id IS NULL OR a.owner_uuid <> c.plot_owner_uuid OR a.civ_id <> c.civ_id
                       OR a.acquired_at <> c.purchased_at OR a.next_due <= ?)
                  AND NOT EXISTS (SELECT 1 FROM plot_tax_bills b WHERE b.claim_id = c.id
                      AND b.status IN ('PENDING','REFUND_PENDING'))
                ORDER BY COALESCE(a.next_due, c.purchased_at), c.id LIMIT 100
                """, clock.instant()); var result = statement.executeQuery()) {
                while (result.next()) ids.add(result.getLong(1));
            }
            return ids;
        }).thenCompose(ids -> {
            CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
            for (long id : ids) chain = chain.thenCompose(ignored -> !enabled() ? CompletableFuture.completedFuture(null)
                : database.transaction(connection -> assess(connection, id)));
            return chain;
        }).thenCompose(ignored -> database.read(connection -> {
            List<UUID> ids = new ArrayList<>();
            try (var statement = connection.prepareStatement("""
                SELECT b.operation_id FROM plot_tax_bills b JOIN economy_operations e ON e.operation_id = b.operation_id
                WHERE b.status IN ('PENDING','REFUND_PENDING')
                  AND e.state NOT IN ('WITHDRAWAL_IN_FLIGHT','REFUND_IN_FLIGHT')
                ORDER BY e.updated_at, b.due_at, b.claim_id LIMIT 100
                """); var result = statement.executeQuery()) {
                while (result.next()) ids.add(UuidBytes.get(result, "operation_id"));
            }
            return ids;
        })).thenCompose(ids -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (UUID id : ids) chain = chain.thenCompose(ignored -> process(id, false));
            return chain;
        }).whenComplete((ignored, failure) -> running.set(false));
    }

    UUID assess(Connection connection, long claimId) throws Exception {
        Plot plot = lockPlot(connection, claimId);
        if (plot == null || !plot.active() || !taxable(plot.type(), plot.owner(), plot.listingKind())) return null;
        Account account = account(connection, claimId);
        Instant now = clock.instant();
        if (!sameOwnership(plot, account)) {
            startOwnership(connection, claimId, plot.civ(), plot.owner(), plot.acquired(), now, rate(connection, plot.civ()));
            return null;
        }
        if (account.due().isAfter(now) || pendingPurchase(connection, claimId)) return null;
        try (var statement = Sql.prepare(connection, """
            SELECT operation_id FROM plot_tax_bills WHERE tenure_id = ? AND due_at = ? FOR UPDATE
            """, account.tenure(), account.due()); var result = statement.executeQuery()) {
            if (result.next()) return UuidBytes.get(result, "operation_id");
        }
        if (account.amount().signum() == 0) {
            advance(connection, account, rate(connection, plot.civ()), now);
            return null;
        }
        UUID operation = UUID.randomUUID();
        try (var statement = Sql.prepare(connection, """
            INSERT INTO economy_operations(operation_id, operation_type, player_uuid, civ_id, amount, state,
                context_json, created_at, updated_at) VALUES (?, 'PLOT_TAX', ?, ?, ?, 'PENDING', ?, ?, ?)
            """, operation, plot.owner(), plot.civ(), account.amount(),
            JsonData.object(Map.of("claim_id", claimId, "tenure_id", account.tenure().toString())), now, now)) {
            statement.executeUpdate();
        }
        try (var statement = Sql.prepare(connection, """
            INSERT INTO plot_tax_bills(operation_id, tenure_id, claim_id, civ_id, owner_uuid, world_name,
                chunk_x, chunk_z, amount, due_at, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
            """, operation, account.tenure(), claimId, plot.civ(), plot.owner(), plot.world(), plot.x(), plot.z(),
            account.amount(), account.due())) { statement.executeUpdate(); }
        return operation;
    }

    private CompletableFuture<Void> process(UUID id, boolean afterWithdrawal) {
        if (!enabled()) return CompletableFuture.completedFuture(null);
        return database.transaction(connection -> settle(connection, id, afterWithdrawal)).thenCompose(stage -> {
            CompletableFuture<Void> refreshed = stage.changed() ? cache.refreshAfterMutation().thenApply(ignored -> null)
                : CompletableFuture.completedFuture(null);
            return refreshed.thenCompose(ignored -> switch (stage.action()) {
                case CHARGE -> !enabled() ? CompletableFuture.completedFuture(null)
                    : economy.resumeCharge(id).thenCompose(charge -> process(id, true));
                case REFUND -> !enabled() ? CompletableFuture.completedFuture(null)
                    : economy.refundCharge(new EconomyService.Charge(id, stage.bill().amount(), OperationResult.ok("Tax refund")),
                        stage.bill().owner()).thenCompose(result -> result.success() ? process(id, true)
                            : CompletableFuture.completedFuture(null));
                case WAIT -> CompletableFuture.completedFuture(null);
            });
        });
    }

    Stage settle(Connection connection, UUID id, boolean afterWithdrawal) throws Exception {
        Bill bill = bill(connection, id);
        if (bill == null || !(bill.status().equals("PENDING") || bill.status().equals("REFUND_PENDING"))) return Stage.waiting();
        Plot plot = lockPlot(connection, bill.claimId());
        Account account = account(connection, bill.claimId());
        // Serialize duplicate callbacks on the durable bill, even when the original claim no longer exists.
        try (var statement = Sql.prepare(connection, "SELECT status FROM plot_tax_bills WHERE operation_id = ? FOR UPDATE", id);
             var result = statement.executeQuery()) {
            if (!result.next() || !(result.getString(1).equals("PENDING") || result.getString(1).equals("REFUND_PENDING")))
                return Stage.waiting();
        }
        String state;
        String response;
        try (var statement = Sql.prepare(connection, "SELECT state, provider_response FROM economy_operations WHERE operation_id = ? FOR UPDATE", id);
             var result = statement.executeQuery()) {
            if (!result.next()) throw new IllegalStateException("Tax bill has no payment operation: " + id);
            state = result.getString("state");
            response = Objects.toString(result.getString("provider_response"), "");
        }
        boolean owned = sameOwnership(plot, account) && plot.active() && account.tenure().equals(bill.tenure())
            && plot.civ() == bill.civ() && plot.owner().equals(bill.owner());
        if (state.equals("WITHDRAWAL_IN_FLIGHT") || state.equals("REFUND_IN_FLIGHT")) return Stage.waiting();
        if (state.equals("FAILED") && response.startsWith("REFUNDED:")) {
            finishBill(connection, bill, "CANCELLED");
            return Stage.waiting();
        }
        if (state.equals("COMPENSATION_PENDING") || bill.status().equals("REFUND_PENDING"))
            return new Stage(Action.REFUND, bill, false);
        if (state.equals("EXTERNAL_APPLIED")) {
            if (!owned) {
                try (var statement = Sql.prepare(connection, "UPDATE plot_tax_bills SET status = 'REFUND_PENDING' WHERE operation_id = ?", id)) {
                    statement.executeUpdate();
                }
                return new Stage(Action.REFUND, bill, false);
            }
            BigDecimal before;
            try (var statement = Sql.prepare(connection, "SELECT treasury_balance FROM civilizations WHERE id = ? FOR UPDATE", bill.civ());
                 var result = statement.executeQuery()) { result.next(); before = result.getBigDecimal(1); }
            BigDecimal after = before.add(bill.amount());
            try (var statement = Sql.prepare(connection,
                "UPDATE civilizations SET treasury_balance = ?, row_version = row_version + 1 WHERE id = ?", after, bill.civ())) {
                statement.executeUpdate();
            }
            try (var statement = Sql.prepare(connection, """
                INSERT INTO treasury_ledger(operation_id, civ_id, actor_uuid, amount, prior_balance,
                    resulting_balance, reason, related_type, related_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PLOT_TAX', 'CLAIM', ?, ?)
                """, id, bill.civ(), bill.owner(), bill.amount(), before, after, Long.toString(bill.claimId()), clock.instant())) {
                statement.executeUpdate();
            }
            operationState(connection, id, "COMPLETED", "WEEKLY_PLOT_TAX_PAID");
            finishBill(connection, bill, "PAID");
            advance(connection, account, rate(connection, bill.civ()), clock.instant());
            audit(connection, bill, "PLOT_TAX_PAID");
            return new Stage(Action.WAIT, bill, true);
        }
        if (!owned) {
            if (state.equals("PENDING") || state.equals("FAILED")) {
                operationState(connection, id, "FAILED", "TAX_CANCELLED_OWNERSHIP_CHANGED");
                finishBill(connection, bill, "CANCELLED");
            }
            return Stage.waiting();
        }
        if (pendingPurchase(connection, bill.claimId())) return Stage.waiting();
        if (state.equals("FAILED") && response.startsWith(EconomyPort.Result.INSUFFICIENT_FUNDS_PREFIX)) {
            try (var statement = Sql.prepare(connection, "DELETE FROM plot_trust WHERE claim_id = ?", bill.claimId())) { statement.executeUpdate(); }
            try (var statement = Sql.prepare(connection, """
                UPDATE civ_claims SET plot_type = 'CIVIC', plot_owner_uuid = NULL, listing_kind = NULL,
                    listing_seller_uuid = NULL, listing_price = NULL, original_purchase_price = NULL,
                    plot_flags = NULL, home_label = NULL, greeting = NULL, listed_at = NULL,
                    purchased_at = NULL, purchased_by = NULL, row_version = row_version + 1 WHERE id = ?
                """, bill.claimId())) { statement.executeUpdate(); }
            try (var statement = Sql.prepare(connection, "DELETE FROM plot_tax_accounts WHERE claim_id = ?", bill.claimId())) { statement.executeUpdate(); }
            finishBill(connection, bill, "RECLAIMED");
            audit(connection, bill, "PLOT_TAX_RECLAIMED");
            return new Stage(Action.WAIT, bill, true);
        }
        if (state.equals("FAILED")) {
            // A definite provider error is retryable, but is not evidence the owner cannot afford the tax.
            operationState(connection, id, "PENDING", "TAX_PROVIDER_RETRY");
            return Stage.waiting();
        }
        return state.equals("PENDING") && !afterWithdrawal ? new Stage(Action.CHARGE, bill, false) : Stage.waiting();
    }

    private void audit(Connection connection, Bill bill, String action) throws Exception {
        AuditLog.write(connection, bill.civ(), bill.owner(), action, "CLAIM", Long.toString(bill.claimId()),
            Map.of("operation", bill.id().toString(), "amount", bill.amount().toPlainString(), "due_at", bill.due().toString()), serverId);
    }

    private void operationState(Connection connection, UUID id, String state, String response) throws Exception {
        try (var statement = Sql.prepare(connection,
            "UPDATE economy_operations SET state = ?, provider_response = ?, updated_at = ? WHERE operation_id = ?",
            state, response, clock.instant(), id)) { statement.executeUpdate(); }
    }

    private void finishBill(Connection connection, Bill bill, String status) throws Exception {
        try (var statement = Sql.prepare(connection,
            "UPDATE plot_tax_bills SET status = ?, settled_at = ? WHERE operation_id = ?", status, clock.instant(), bill.id())) {
            statement.executeUpdate();
        }
    }

    private static void advance(Connection connection, Account account, BigDecimal rate, Instant now) throws Exception {
        try (var statement = Sql.prepare(connection,
            "UPDATE plot_tax_accounts SET next_due = ?, weekly_amount = ? WHERE claim_id = ? AND tenure_id = ?",
            now.plus(WEEK), rate, account.claimId(), account.tenure())) { statement.executeUpdate(); }
    }

    static boolean sameOwnership(Plot plot, Account account) {
        return plot != null && account != null && taxable(plot.type(), plot.owner(), plot.listingKind())
            && plot.civ() == account.civ() && plot.owner().equals(account.owner()) && Objects.equals(plot.acquired(), account.acquired());
    }

    private Plot lockPlot(Connection connection, long claimId) throws Exception {
        // Lock civilization before claim, matching territorial mutations. Recheck civilization after obtaining the claim lock.
        long civ;
        try (var statement = Sql.prepare(connection, "SELECT civ_id FROM civ_claims WHERE id = ?", claimId);
             var result = statement.executeQuery()) { if (!result.next()) return null; civ = result.getLong(1); }
        boolean active;
        try (var statement = Sql.prepare(connection, "SELECT status FROM civilizations WHERE id = ? FOR UPDATE", civ);
             var result = statement.executeQuery()) { active = result.next() && result.getString(1).equals("ACTIVE"); }
        try (var statement = Sql.prepare(connection, "SELECT * FROM civ_claims WHERE id = ? FOR UPDATE", claimId);
             var result = statement.executeQuery()) {
            if (!result.next() || result.getLong("civ_id") != civ) return null;
            var acquired = result.getTimestamp("purchased_at");
            return new Plot(claimId, civ, UuidBytes.get(result, "plot_owner_uuid"), PlotType.valueOf(result.getString("plot_type")),
                result.getString("listing_kind"), acquired == null ? null : acquired.toInstant(), active,
                result.getString("world_name"), result.getInt("chunk_x"), result.getInt("chunk_z"));
        }
    }

    private Account account(Connection connection, long claimId) throws Exception {
        try (var statement = Sql.prepare(connection, "SELECT * FROM plot_tax_accounts WHERE claim_id = ? FOR UPDATE", claimId);
             var result = statement.executeQuery()) {
            return result.next() ? new Account(claimId, UuidBytes.get(result, "tenure_id"), result.getLong("civ_id"),
                UuidBytes.get(result, "owner_uuid"), result.getTimestamp("acquired_at").toInstant(),
                result.getBigDecimal("weekly_amount"), result.getTimestamp("next_due").toInstant()) : null;
        }
    }

    private Bill bill(Connection connection, UUID id) throws Exception {
        try (var statement = Sql.prepare(connection, "SELECT * FROM plot_tax_bills WHERE operation_id = ?", id);
             var result = statement.executeQuery()) {
            return result.next() ? new Bill(id, UuidBytes.get(result, "tenure_id"), result.getLong("claim_id"),
                result.getLong("civ_id"), UuidBytes.get(result, "owner_uuid"), result.getBigDecimal("amount"),
                result.getTimestamp("due_at").toInstant(), result.getString("status")) : null;
        }
    }

    private boolean pendingPurchase(Connection connection, long claimId) throws Exception {
        try (var statement = Sql.prepare(connection, """
            SELECT 1 FROM economy_operations WHERE claim_id = ? AND operation_type = 'PLOT_PURCHASE'
              AND state NOT IN ('FAILED','COMPLETED') LIMIT 1
            """, claimId); var result = statement.executeQuery()) { return result.next(); }
    }

    public CompletableFuture<List<Notice>> notices(UUID player) {
        return database.read(connection -> {
            List<Notice> notices = new ArrayList<>();
            try (var statement = Sql.prepare(connection, """
                SELECT * FROM plot_tax_bills WHERE owner_uuid = ? AND notified = FALSE
                  AND status IN ('PAID','RECLAIMED') ORDER BY due_at, claim_id LIMIT 50
                """, player); var result = statement.executeQuery()) {
                while (result.next()) notices.add(new Notice(UuidBytes.get(result, "operation_id"), result.getString("status"),
                    result.getString("world_name"), result.getInt("chunk_x"), result.getInt("chunk_z"), result.getBigDecimal("amount")));
            }
            return List.copyOf(notices);
        });
    }

    public CompletableFuture<Void> acknowledge(UUID player, List<Notice> notices) {
        return database.transaction(connection -> {
            for (Notice notice : notices) try (var statement = Sql.prepare(connection,
                "UPDATE plot_tax_bills SET notified = TRUE WHERE operation_id = ? AND owner_uuid = ?", notice.id(), player)) {
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override public void close() { closed = true; }

    public record PlotTaxView(long claimId, String world, int x, int z, String label, BigDecimal amount, Instant due, String paymentState) {}
    public record TaxOverview(long civilizationId, BigDecimal rate, boolean leader, List<PlotTaxView> plots, boolean enabled) {}
    public record Notice(UUID id, String status, String world, int x, int z, BigDecimal amount) {}
    record Plot(long id, long civ, UUID owner, PlotType type, String listingKind, Instant acquired, boolean active, String world, int x, int z) {}
    record Account(long claimId, UUID tenure, long civ, UUID owner, Instant acquired, BigDecimal amount, Instant due) {}
    record Bill(UUID id, UUID tenure, long claimId, long civ, UUID owner, BigDecimal amount, Instant due, String status) {}
    enum Action { CHARGE, REFUND, WAIT }
    record Stage(Action action, Bill bill, boolean changed) { static Stage waiting() { return new Stage(Action.WAIT, null, false); } }
}
