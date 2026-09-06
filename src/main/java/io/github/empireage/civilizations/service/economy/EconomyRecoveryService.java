package io.github.empireage.civilizations.service.economy;

import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.database.Sql;
import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.service.territory.EconomyPort;
import io.github.empireage.civilizations.service.territory.PlotService;
import io.github.empireage.civilizations.util.UuidBytes;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Bounded recovery for transfers whose provider call is known not to be ambiguous. */
public final class EconomyRecoveryService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Database database;
    private final EconomyService economy;
    private final PlotService plots;
    private final TreasuryService treasury;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private BukkitTask task;

    /**
     * Compatibility constructor. Treasury recovery requires the five-argument
     * constructor so domain balance and ledger mutations stay centralized.
     */
    public EconomyRecoveryService(JavaPlugin plugin, Database database, EconomyService economy, PlotService plots) {
        this(plugin, database, economy, plots, null);
    }

    public EconomyRecoveryService(JavaPlugin plugin, Database database, EconomyService economy, PlotService plots,
                                  TreasuryService treasury) {
        this.plugin = plugin;
        this.database = database;
        this.economy = economy;
        this.plots = plots;
        this.treasury = treasury;
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::recover, 20L * 30, 20L * 60);
    }

    public void recover() {
        if (!economy.enabled() || !database.healthy() || !running.compareAndSet(false, true)) return;
        database.read(this::loadRecoverable).thenCompose(operations -> {
            CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
            for (Pending operation : operations) {
                chain = chain.thenCompose(ignored -> recoverOne(operation).exceptionally(error -> {
                    plugin.getLogger().log(Level.WARNING,
                        "Economy recovery failed for operation " + operation.id() + ": " + rootMessage(error));
                    return null;
                }));
            }
            return chain;
        }).whenComplete((ignored, error) -> {
            if (error != null) plugin.getLogger().log(Level.WARNING, "Economy recovery scan failed", error);
            running.set(false);
        });
    }

    private List<Pending> loadRecoverable(Connection connection) throws Exception {
        List<Pending> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT operation_id, operation_type, player_uuid, beneficiary_uuid, civ_id, amount, state, provider_response
            FROM economy_operations
            WHERE updated_at < ? AND (
                   (operation_type = 'PLOT_PURCHASE'
                    AND state IN ('PENDING','EXTERNAL_APPLIED','DB_APPLIED','COMPENSATION_PENDING'))
                OR (operation_type = 'DISBAND_PAYOUT' AND state = 'PENDING_DELIVERY')
                OR (operation_type = 'TREASURY_WITHDRAWAL' AND state = 'DB_APPLIED')
                OR (operation_type = 'TREASURY_DEPOSIT'
                    AND state IN ('PENDING','EXTERNAL_APPLIED','COMPENSATION_PENDING'))
                OR (operation_type = 'CIVILIZATION_FOUNDING'
                    AND state IN ('EXTERNAL_APPLIED','COMPENSATION_PENDING'))
            ) ORDER BY updated_at LIMIT 50
            """)) {
            statement.setTimestamp(1, java.sql.Timestamp.from(Instant.now().minusSeconds(10)));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long rawCivilizationId = result.getLong("civ_id");
                    Long civilizationId = result.wasNull() ? null : rawCivilizationId;
                    values.add(new Pending(UuidBytes.fromBytes(result.getBytes("operation_id")),
                        result.getString("operation_type"), UuidBytes.fromBytes(result.getBytes("player_uuid")),
                        UuidBytes.fromBytes(result.getBytes("beneficiary_uuid")), civilizationId,
                        result.getBigDecimal("amount"), EconomyOperationState.valueOf(result.getString("state")),
                        Objects.toString(result.getString("provider_response"), "")));
                }
            }
        }
        return List.copyOf(values);
    }

    private CompletableFuture<?> recoverOne(Pending operation) {
        return switch (operation.type()) {
            case "PLOT_PURCHASE" -> plots.resumePurchase(operation.id());
            case "DISBAND_PAYOUT" -> operation.state() == EconomyOperationState.PENDING_DELIVERY
                ? deliverDisbandPayout(operation.id()) : CompletableFuture.completedFuture(null);
            case "TREASURY_WITHDRAWAL" -> treasury != null && operation.state() == EconomyOperationState.DB_APPLIED
                ? treasury.resumeWithdrawal(operation.id()) : CompletableFuture.completedFuture(null);
            case "TREASURY_DEPOSIT" -> treasury != null
                ? treasury.recoverDeposit(operation.id()) : CompletableFuture.completedFuture(null);
            case "CIVILIZATION_FOUNDING" -> recoverFounding(operation);
            default -> CompletableFuture.completedFuture(null);
        };
    }

    private CompletableFuture<?> recoverFounding(Pending operation) {
        if (operation.state() == EconomyOperationState.EXTERNAL_APPLIED && operation.civilizationId() != null) {
            // The lifecycle transaction attached this id atomically with the
            // civilization. Only the bookkeeping acknowledgement was lost.
            return markFoundingCommitted(operation.id(), operation.civilizationId());
        }
        if ((operation.state() == EconomyOperationState.EXTERNAL_APPLIED
            || operation.state() == EconomyOperationState.COMPENSATION_PENDING)
            && operation.civilizationId() == null && operation.player() != null) {
            EconomyService.Charge charge = new EconomyService.Charge(operation.id(), operation.amount(),
                OperationResult.ok("Founding charge was applied."));
            return economy.refundCharge(charge, operation.player());
        }
        // PENDING means no provider call began, but founding materials were an
        // in-memory inventory debit. Charging after restart would be unsafe.
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<?> deliverDisbandPayout(UUID operationId) {
        return database.transaction(connection -> claimDisbandPayout(connection, operationId))
            .thenCompose(claim -> {
                if (claim == null) return CompletableFuture.completedFuture(null);
                return economy.port().deposit(claim.id(), claim.beneficiary(), claim.amount())
                    .exceptionally(error -> EconomyPort.Result.ambiguous(rootMessage(error)))
                    .thenCompose(result -> recordDisbandDelivery(claim.id(), result));
            });
    }

    private PayoutClaim claimDisbandPayout(Connection connection, UUID operationId) throws Exception {
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT operation_id, player_uuid, beneficiary_uuid, amount, state
            FROM economy_operations WHERE operation_id = ? AND operation_type = 'DISBAND_PAYOUT' FOR UPDATE
            """, operationId); ResultSet result = statement.executeQuery()) {
            if (!result.next() || EconomyOperationState.valueOf(result.getString("state"))
                != EconomyOperationState.PENDING_DELIVERY) return null;
            UUID player = UuidBytes.fromBytes(result.getBytes("player_uuid"));
            UUID beneficiary = UuidBytes.fromBytes(result.getBytes("beneficiary_uuid"));
            PayoutClaim claim = new PayoutClaim(UuidBytes.fromBytes(result.getBytes("operation_id")),
                beneficiary == null ? player : beneficiary, result.getBigDecimal("amount"));
            try (PreparedStatement update = Sql.prepare(connection, """
                UPDATE economy_operations SET state = 'DELIVERY_IN_FLIGHT', provider_response = ?,
                    retry_count = retry_count + 1, updated_at = ?
                WHERE operation_id = ? AND state = 'PENDING_DELIVERY'
                """, "PAYOUT_DISPATCHED", Instant.now(), operationId)) {
                if (update.executeUpdate() != 1) return null;
            }
            return claim;
        }
    }

    private CompletableFuture<Void> recordDisbandDelivery(UUID id, EconomyPort.Result result) {
        return database.transaction(connection -> {
            if (result.ambiguous()) {
                try (PreparedStatement statement = Sql.prepare(connection, """
                    UPDATE economy_operations SET provider_response = ?, retry_count = retry_count + 1, updated_at = ?
                    WHERE operation_id = ? AND state = 'DELIVERY_IN_FLIGHT'
                    """, truncate(result.providerMessage()), Instant.now(), id)) {
                    statement.executeUpdate();
                }
                return null;
            }
            try (PreparedStatement statement = Sql.prepare(connection, """
                UPDATE economy_operations SET state = ?, provider_response = ?, retry_count = retry_count + 1,
                    updated_at = ? WHERE operation_id = ? AND state = 'DELIVERY_IN_FLIGHT'
                """, result.success() ? EconomyOperationState.COMPLETED : EconomyOperationState.PENDING_DELIVERY,
                truncate(result.providerMessage()), Instant.now(), id)) {
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Disband payout state changed before its provider result was recorded");
                }
            }
            return null;
        });
    }

    private CompletableFuture<Void> markFoundingCommitted(UUID id, long civilizationId) {
        return database.transaction(connection -> {
            try (PreparedStatement statement = Sql.prepare(connection, """
                UPDATE economy_operations SET state = 'COMPLETED', provider_response = ?, updated_at = ?
                WHERE operation_id = ? AND operation_type = 'CIVILIZATION_FOUNDING'
                  AND state = 'EXTERNAL_APPLIED' AND civ_id = ?
                """, "FOUNDING_COMMIT_RECOVERED", Instant.now(), id, civilizationId)) {
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void close() {
        if (task != null) task.cancel();
        task = null;
    }

    private static String truncate(String value) {
        String safe = value == null ? "" : value;
        return safe.length() <= 512 ? safe : safe.substring(0, 512);
    }

    private static String rootMessage(Throwable failure) {
        Throwable cursor = failure;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private record Pending(UUID id, String type, UUID player, UUID beneficiary, Long civilizationId,
                           BigDecimal amount, EconomyOperationState state, String providerMessage) {}

    private record PayoutClaim(UUID id, UUID beneficiary, BigDecimal amount) {}
}
