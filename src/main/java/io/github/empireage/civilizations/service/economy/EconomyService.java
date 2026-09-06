package io.github.empireage.civilizations.service.economy;

import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.database.JsonData;
import io.github.empireage.civilizations.database.Sql;
import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.service.territory.EconomyPort;
import io.github.empireage.civilizations.util.UuidBytes;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Coordinates durable charge records with an optional Vault provider. */
public final class EconomyService {
    private final Database database;
    private final Settings settings;
    private final EconomyPort port;
    private final VaultEconomyPort vault;
    private final Clock clock;

    public EconomyService(JavaPlugin plugin, Database database, Settings settings) {
        this.database = database;
        this.settings = settings;
        this.clock = Clock.systemUTC();
        VaultEconomyPort discovered = settings.economyMode() == Settings.EconomyMode.VAULT ? VaultEconomyPort.discover(plugin) : null;
        this.vault = discovered;
        this.port = discovered == null ? EconomyPort.DISABLED : discovered;
        if (settings.economyMode() == Settings.EconomyMode.VAULT && discovered == null) {
            plugin.getLogger().warning("economy.mode is VAULT, but no Vault Economy provider is available. Money features are disabled.");
        } else if (discovered != null) {
            plugin.getLogger().info("Using Vault economy provider: " + discovered.providerName());
        }
    }

    public EconomyPort port() { return port; }
    public boolean enabled() { return settings.economyMode() == Settings.EconomyMode.VAULT && vault != null; }

    public CompletableFuture<BigDecimal> balance(UUID playerId) {
        return vault == null ? CompletableFuture.completedFuture(BigDecimal.ZERO.setScale(2)) : vault.balance(playerId);
    }

    public CompletableFuture<Charge> reserveCharge(UUID playerId, BigDecimal amount, String operationType) {
        return reserveCharge(playerId, amount, operationType, null, Map.of());
    }

    /**
     * Persists the complete intent before dispatching a withdrawal. The
     * civilization id is an intended domain target, not proof that the domain
     * mutation committed (except for a founding operation, whose id is attached
     * atomically by the lifecycle transaction).
     */
    public CompletableFuture<Charge> reserveCharge(UUID playerId, BigDecimal amount, String operationType,
                                                    Long civilizationId, Map<String, ?> context) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(operationType, "operationType");
        BigDecimal normalized = amount.setScale(2, RoundingMode.UNNECESSARY);
        if (settings.economyMode() == Settings.EconomyMode.DISABLED) {
            return CompletableFuture.completedFuture(new Charge(null, BigDecimal.ZERO.setScale(2),
                OperationResult.ok("Currency costs are disabled.")));
        }
        if (normalized.signum() == 0) {
            return CompletableFuture.completedFuture(new Charge(null, normalized, OperationResult.ok("No currency charge required.")));
        }
        if (!enabled()) {
            return CompletableFuture.completedFuture(new Charge(null, normalized,
                OperationResult.denied("This action requires Vault economy, but money features are disabled.")));
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        return database.transaction(connection -> {
            lockChargeCoordinator(connection, operationType, playerId);
            try (PreparedStatement existing = Sql.prepare(connection, """
                SELECT operation_id, state FROM economy_operations
                WHERE player_uuid = ? AND operation_type = ?
                  AND state NOT IN ('COMPLETED','FAILED')
                ORDER BY created_at LIMIT 1 FOR UPDATE
                """, playerId, operationType); ResultSet result = existing.executeQuery()) {
                if (result.next()) {
                    UUID existingId = UuidBytes.fromBytes(result.getBytes("operation_id"));
                    String existingState = result.getString("state");
                    return Reservation.conflict(new Charge(existingId, normalized, OperationResult.denied(
                        "A previous " + operationType.toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                            + " payment is unresolved (state " + existingState + "). Operation " + existingId
                            + " must be reconciled before another is started.")));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economy_operations(operation_id, operation_type, player_uuid, civ_id, amount, state,
                    context_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
                """)) {
                Sql.bind(statement, id, operationType, playerId, civilizationId, normalized,
                    JsonData.object(context == null ? Map.of() : context), now, now);
                statement.executeUpdate();
            }
            return Reservation.created(id);
        }).thenCompose(reservation -> reservation.conflict() == null
            ? resumeCharge(reservation.id())
            : CompletableFuture.completedFuture(reservation.conflict()));
    }

    /** Safely resumes a charge only when its provider invocation never began. */
    public CompletableFuture<Charge> resumeCharge(UUID operationId) {
        return database.transaction(connection -> claimWithdrawal(connection, operationId))
            .thenCompose(stage -> {
                if (!stage.dispatch()) return CompletableFuture.completedFuture(stage.charge());
                CompletableFuture<EconomyPort.Result> withdrawal = "PLOT_TAX".equals(stage.operation().type())
                    ? port.withdrawTax(stage.operation().id(), stage.operation().player(), stage.operation().amount())
                    : port.withdraw(stage.operation().id(), stage.operation().player(), stage.operation().amount());
                return withdrawal
                    .exceptionally(error -> EconomyPort.Result.ambiguous(rootMessage(error)))
                    .thenCompose(result -> recordWithdrawalResult(stage.operation(), result));
            });
    }

    private ChargeStage claimWithdrawal(Connection connection, UUID operationId) throws Exception {
        ChargeOperation operation = lockOperation(connection, operationId);
        if (operation == null) return ChargeStage.done(new Charge(operationId, BigDecimal.ZERO.setScale(2),
            OperationResult.denied("Unknown payment operation.")));
        return switch (operation.state()) {
            case PENDING -> {
                updateState(connection, operation.id(), EconomyOperationState.PENDING,
                    EconomyOperationState.WITHDRAWAL_IN_FLIGHT, "WITHDRAWAL_DISPATCHED");
                yield ChargeStage.dispatch(operation);
            }
            case EXTERNAL_APPLIED -> ChargeStage.done(successfulCharge(operation, "Currency was already reserved."));
            case COMPLETED -> ChargeStage.done(successfulCharge(operation, "Payment was already completed."));
            case WITHDRAWAL_IN_FLIGHT -> ChargeStage.done(deniedCharge(operation,
                "The payment outcome is ambiguous and requires administrator reconciliation; it will not be charged again automatically."));
            case FAILED -> ChargeStage.done(deniedCharge(operation, "The payment operation has failed."));
            default -> ChargeStage.done(deniedCharge(operation, "The payment operation cannot be used as a charge in state "
                + operation.state() + "."));
        };
    }

    private CompletableFuture<Charge> recordWithdrawalResult(ChargeOperation operation, EconomyPort.Result result) {
        if (result.ambiguous()) {
            return recordAmbiguous(operation.id(), EconomyOperationState.WITHDRAWAL_IN_FLIGHT, result.providerMessage())
                .thenApply(ignored -> deniedCharge(operation,
                    "The economy provider outcome is unknown. The operation is locked for administrator reconciliation."));
        }
        EconomyOperationState next = result.success() ? EconomyOperationState.EXTERNAL_APPLIED : EconomyOperationState.FAILED;
        return database.transaction(connection -> {
            String response = result.providerMessage();
            if ("PLOT_TAX".equals(operation.type()) && !result.success()) {
                response = result.verifiedInsufficientFunds()
                    ? EconomyPort.Result.INSUFFICIENT_FUNDS_PREFIX + " " + response
                    : "PROVIDER_ERROR: " + response;
            }
            updateState(connection, operation.id(), EconomyOperationState.WITHDRAWAL_IN_FLIGHT, next, response);
            return result.success() ? successfulCharge(operation, "Currency reserved.")
                : deniedCharge(operation, "Payment failed: " + result.providerMessage());
        });
    }

    public CompletableFuture<OperationResult> completeCharge(Charge charge, Long civilizationId) {
        if (charge.operationId() == null) return CompletableFuture.completedFuture(charge.result());
        return database.transaction(connection -> {
            ChargeOperation operation = lockOperation(connection, charge.operationId());
            if (operation == null) return OperationResult.denied("The payment operation no longer exists.");
            if (operation.state() == EconomyOperationState.COMPLETED) {
                return Objects.equals(operation.civilizationId(), civilizationId)
                    ? OperationResult.ok("Payment was already completed.")
                    : OperationResult.denied("The completed payment belongs to another civilization.");
            }
            if (operation.state() != EconomyOperationState.EXTERNAL_APPLIED) {
                return OperationResult.denied("The payment operation was not in a completable state.");
            }
            if (operation.civilizationId() != null && !Objects.equals(operation.civilizationId(), civilizationId)) {
                return OperationResult.denied("The payment is attached to another civilization.");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE economy_operations SET civ_id = ?, state = 'COMPLETED', updated_at = ?
                WHERE operation_id = ? AND state = 'EXTERNAL_APPLIED'
                """)) {
                Sql.bind(statement, civilizationId, clock.instant(), charge.operationId());
                if (statement.executeUpdate() != 1) throw new IllegalStateException("Payment state changed while completing it");
            }
            return OperationResult.ok("Payment completed.");
        });
    }

    /** Returns the civilization atomically attached to a founding charge, if any. */
    public CompletableFuture<Long> attachedFoundingCivilization(UUID operationId) {
        if (operationId == null) return CompletableFuture.completedFuture(null);
        return database.read(connection -> {
            try (PreparedStatement statement = Sql.prepare(connection, """
                SELECT civ_id FROM economy_operations
                WHERE operation_id = ? AND operation_type = 'CIVILIZATION_FOUNDING'
                """, operationId); ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                long civilizationId = result.getLong(1);
                return result.wasNull() ? null : civilizationId;
            }
        });
    }

    /**
     * Compensates a known-applied withdrawal. A founding charge that was
     * atomically attached to a civilization is never refunded here, preventing
     * a commit-acknowledgement failure from creating a free civilization.
     */
    public CompletableFuture<OperationResult> refundCharge(Charge charge, UUID playerId) {
        if (charge.operationId() == null || charge.amount().signum() == 0) {
            return CompletableFuture.completedFuture(OperationResult.ok("No refund required."));
        }
        return database.transaction(connection -> claimRefund(connection, charge.operationId(), playerId))
            .thenCompose(stage -> {
                if (!stage.dispatch()) return CompletableFuture.completedFuture(stage.result());
                ChargeOperation operation = stage.operation();
                return port.refund(operation.id(), operation.player(), operation.amount())
                    .exceptionally(error -> EconomyPort.Result.ambiguous(rootMessage(error)))
                    .thenCompose(result -> recordRefundResult(operation, result));
            });
    }

    private RefundStage claimRefund(Connection connection, UUID operationId, UUID requestedPlayer) throws Exception {
        ChargeOperation operation = lockOperation(connection, operationId);
        if (operation == null) return RefundStage.done(OperationResult.denied("The payment operation no longer exists."));
        if (!Objects.equals(operation.player(), requestedPlayer)) {
            return RefundStage.done(OperationResult.denied("The payment belongs to another account."));
        }
        if (operation.state() == EconomyOperationState.FAILED
            && operation.providerResponse().startsWith("REFUNDED:")) {
            return RefundStage.done(OperationResult.ok("Payment was already refunded."));
        }
        if (operation.state() == EconomyOperationState.REFUND_IN_FLIGHT) {
            return RefundStage.done(OperationResult.denied(
                "The refund outcome is ambiguous and requires administrator reconciliation; it will not be replayed automatically."));
        }
        if (operation.state() != EconomyOperationState.EXTERNAL_APPLIED
            && operation.state() != EconomyOperationState.COMPENSATION_PENDING) {
            return RefundStage.done(OperationResult.denied("The payment is not safely refundable in state " + operation.state() + "."));
        }
        if ("CIVILIZATION_FOUNDING".equals(operation.type()) && operation.civilizationId() != null) {
            return RefundStage.done(OperationResult.denied(
                "The founding payment is attached to a committed civilization and cannot be refunded automatically."));
        }
        updateState(connection, operation.id(), operation.state(), EconomyOperationState.REFUND_IN_FLIGHT,
            "REFUND_DISPATCHED");
        return RefundStage.dispatch(operation);
    }

    private CompletableFuture<OperationResult> recordRefundResult(ChargeOperation operation, EconomyPort.Result result) {
        if (result.ambiguous()) {
            return recordAmbiguous(operation.id(), EconomyOperationState.REFUND_IN_FLIGHT, result.providerMessage())
                .thenApply(ignored -> OperationResult.denied(
                    "The refund outcome is unknown and requires administrator reconciliation."));
        }
        EconomyOperationState next = result.success() ? EconomyOperationState.FAILED
            : EconomyOperationState.COMPENSATION_PENDING;
        String response = (result.success() ? "REFUNDED:" : "REFUND_PENDING:") + result.providerMessage();
        return database.transaction(connection -> {
            updateState(connection, operation.id(), EconomyOperationState.REFUND_IN_FLIGHT, next, response);
            return result.success() ? OperationResult.ok("Payment refunded.")
                : OperationResult.denied("Refund is queued for administrator retry.");
        });
    }

    private CompletableFuture<Void> recordAmbiguous(UUID operationId, EconomyOperationState expected, String response) {
        return database.transaction(connection -> {
            try (PreparedStatement statement = Sql.prepare(connection, """
                UPDATE economy_operations SET provider_response = ?, retry_count = retry_count + 1, updated_at = ?
                WHERE operation_id = ? AND state = ?
                """, truncate(response), clock.instant(), operationId, expected)) {
                statement.executeUpdate();
            }
            return null;
        });
    }

    private ChargeOperation lockOperation(Connection connection, UUID operationId) throws Exception {
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT operation_id, operation_type, player_uuid, civ_id, amount, state, provider_response
            FROM economy_operations WHERE operation_id = ? FOR UPDATE
            """, operationId); ResultSet result = statement.executeQuery()) {
            if (!result.next()) return null;
            long rawCivilizationId = result.getLong("civ_id");
            Long civilizationId = result.wasNull() ? null : rawCivilizationId;
            return new ChargeOperation(UuidBytes.fromBytes(result.getBytes("operation_id")),
                result.getString("operation_type"), UuidBytes.fromBytes(result.getBytes("player_uuid")),
                civilizationId, result.getBigDecimal("amount"),
                EconomyOperationState.valueOf(result.getString("state")),
                Objects.toString(result.getString("provider_response"), ""));
        }
    }

    private void lockChargeCoordinator(Connection connection, String operationType, UUID playerId) throws Exception {
        String sql = switch (operationType) {
            case "CIVILIZATION_FOUNDING" ->
                "SELECT lock_key FROM civ_coordination_locks WHERE lock_key = 'founding' FOR UPDATE";
            case "TREASURY_DEPOSIT" ->
                "SELECT player_uuid FROM civ_members WHERE player_uuid = ? FOR UPDATE";
            default -> null;
        };
        if (sql == null) return;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if ("TREASURY_DEPOSIT".equals(operationType)) statement.setBytes(1, UuidBytes.toBytes(playerId));
            try (ResultSet ignored = statement.executeQuery()) {
                // Acquiring the row lock, including an empty result for an
                // invalid request, is the only purpose of this query.
                while (ignored.next()) { /* consume the locking read */ }
            }
        }
    }

    private void updateState(Connection connection, UUID id, EconomyOperationState expected,
                             EconomyOperationState next, String providerResponse) throws Exception {
        try (PreparedStatement statement = Sql.prepare(connection, """
            UPDATE economy_operations SET state = ?, provider_response = ?, retry_count = retry_count + 1,
                updated_at = ? WHERE operation_id = ? AND state = ?
            """, next, truncate(providerResponse), clock.instant(), id, expected)) {
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Economy operation state changed concurrently");
        }
    }

    private static Charge successfulCharge(ChargeOperation operation, String message) {
        return new Charge(operation.id(), operation.amount(), OperationResult.ok(message));
    }

    private static Charge deniedCharge(ChargeOperation operation, String message) {
        return new Charge(operation.id(), operation.amount(), OperationResult.denied(message));
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

    public record Charge(UUID operationId, BigDecimal amount, OperationResult result) {}

    private record ChargeOperation(UUID id, String type, UUID player, Long civilizationId, BigDecimal amount,
                                   EconomyOperationState state, String providerResponse) {}

    private record ChargeStage(Charge charge, ChargeOperation operation, boolean dispatch) {
        static ChargeStage done(Charge charge) { return new ChargeStage(charge, null, false); }
        static ChargeStage dispatch(ChargeOperation operation) { return new ChargeStage(null, operation, true); }
    }

    private record RefundStage(OperationResult result, ChargeOperation operation, boolean dispatch) {
        static RefundStage done(OperationResult result) { return new RefundStage(result, null, false); }
        static RefundStage dispatch(ChargeOperation operation) { return new RefundStage(null, operation, true); }
    }

    private record Reservation(UUID id, Charge conflict) {
        static Reservation created(UUID id) { return new Reservation(id, null); }
        static Reservation conflict(Charge charge) { return new Reservation(null, charge); }
    }
}
