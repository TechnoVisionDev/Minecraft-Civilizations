package io.github.empireage.civilizations.service.economy;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.concurrent.CivilizationLocks;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.database.AuditLog;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.database.Sql;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.domain.Role;
import io.github.empireage.civilizations.service.territory.EconomyPort;
import io.github.empireage.civilizations.util.TimeUtil;
import io.github.empireage.civilizations.util.UuidBytes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Treasury mutations with durable, crash-conscious Vault sagas. */
public final class TreasuryService {
    private final Database database;
    private final StateCache cache;
    private final CivilizationLocks locks;
    private final Settings settings;
    private final EconomyService economy;
    private final Clock clock = Clock.systemUTC();

    public TreasuryService(Database database, StateCache cache, CivilizationLocks locks, Settings settings, EconomyService economy) {
        this.database = database;
        this.cache = cache;
        this.locks = locks;
        this.settings = settings;
        this.economy = economy;
    }

    public BigDecimal balance(UUID viewer) {
        Member member = cache.snapshot().member(viewer);
        return member == null ? null : cache.snapshot().civilization(member.civilizationId()).treasury();
    }

    public CompletableFuture<OperationResult> deposit(UUID actorId, BigDecimal rawAmount) {
        BigDecimal amount = normalize(rawAmount);
        if (amount == null || amount.signum() <= 0) return denied("Deposit a positive currency amount.");
        if (!economy.enabled()) return denied("Money features are disabled.");
        Member member = cache.snapshot().member(actorId);
        if (member == null) return denied("You do not belong to a civilization.");
        return economy.reserveCharge(actorId, amount, "TREASURY_DEPOSIT", member.civilizationId(),
                Map.of("reason", "DONATION"))
            .thenCompose(charge -> charge.result().success()
                ? commitDeposit(charge, actorId)
                : CompletableFuture.completedFuture(charge.result()));
    }

    /** Recovery entry point for a persisted PENDING/EXTERNAL_APPLIED deposit. */
    public CompletableFuture<OperationResult> recoverDeposit(UUID operationId) {
        return database.read(connection -> loadDeposit(connection, operationId)).thenCompose(operation -> {
            if (operation == null) return denied("Unknown treasury deposit.");
            if (operation.state() == EconomyOperationState.COMPENSATION_PENDING) {
                return economy.refundCharge(operation.charge(), operation.player());
            }
            if (operation.state() == EconomyOperationState.COMPLETED) {
                return CompletableFuture.completedFuture(OperationResult.ok("Treasury deposit was already completed."));
            }
            return economy.resumeCharge(operationId).thenCompose(charge -> charge.result().success()
                ? commitDeposit(charge, operation.player())
                : CompletableFuture.completedFuture(charge.result()));
        });
    }

    private CompletableFuture<OperationResult> commitDeposit(EconomyService.Charge charge, UUID actorId) {
        return database.transaction(connection -> applyDeposit(connection, charge.operationId()))
            .handle((stage, failure) -> {
                if (failure != null) {
                    return economy.refundCharge(charge, actorId).thenApply(refund -> OperationResult.denied(
                        "The treasury deposit could not be confirmed. " + (refund.success()
                            ? "The payment was refunded."
                            : "The durable operation is held for administrator review: " + refund.message())));
                }
                if (stage.refund() != null) {
                    return economy.refundCharge(stage.refund(), stage.player()).thenApply(refund -> OperationResult.denied(
                        stage.result().message() + (refund.success() ? " The payment was refunded."
                            : " " + refund.message())));
                }
                CompletableFuture<?> refresh = stage.mutated() ? cache.refreshAfterMutation() : CompletableFuture.completedFuture(null);
                return refresh.handle((ignored, refreshFailure) -> refreshFailure == null ? stage.result()
                    : OperationResult.ok(stage.result().message()
                        + " Cached balances remain unavailable until the cache reload succeeds."));
            }).thenCompose(value -> value);
    }

    private DepositStage applyDeposit(Connection connection, UUID operationId) throws Exception {
        DepositOperation operation = lockDeposit(connection, operationId);
        if (operation == null) return DepositStage.done(OperationResult.denied("Unknown treasury deposit."));
        if (operation.state() == EconomyOperationState.COMPLETED) {
            return DepositStage.done(OperationResult.ok("Treasury deposit was already completed."));
        }
        if (operation.state() == EconomyOperationState.COMPENSATION_PENDING) {
            return DepositStage.refund(OperationResult.denied("The target civilization cannot receive this deposit."), operation);
        }
        if (operation.state().ambiguous()) {
            return DepositStage.done(OperationResult.denied(
                "The Vault outcome is ambiguous and requires administrator reconciliation."));
        }
        if (operation.state() != EconomyOperationState.EXTERNAL_APPLIED) {
            return DepositStage.done(OperationResult.denied("Treasury deposit is not ready in state " + operation.state() + "."));
        }
        if (operation.civilizationId() == null) {
            return DepositStage.refund(OperationResult.denied("The treasury target is missing."), operation);
        }
        BigDecimal prior = lockTreasuryOrNull(connection, operation.civilizationId());
        if (prior == null) {
            return DepositStage.refund(OperationResult.denied("The target civilization is no longer active."), operation);
        }
        BigDecimal resulting = prior.add(operation.amount());
        updateTreasury(connection, operation.civilizationId(), resulting);
        insertLedger(connection, operation.id(), operation.civilizationId(), operation.player(), null,
            operation.amount(), prior, resulting, "DONATION", null);
        try (PreparedStatement statement = Sql.prepare(connection, """
            UPDATE economy_operations SET state = 'COMPLETED', provider_response = ?, updated_at = ?
            WHERE operation_id = ? AND state = 'EXTERNAL_APPLIED'
            """, "TREASURY_CREDIT_COMMITTED", clock.instant(), operation.id())) {
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Deposit state changed while committing it");
        }
        AuditLog.write(connection, operation.civilizationId(), operation.player(), "treasury.deposit",
            "economy_operation", operation.id().toString(), Map.of("amount", operation.amount().toPlainString()),
            settings.serverId());
        return DepositStage.mutated(OperationResult.ok(
            "Deposited " + operation.amount().toPlainString() + " into the civilization treasury."));
    }

    public CompletableFuture<OperationResult> withdraw(UUID actorId, BigDecimal rawAmount) {
        BigDecimal amount = normalize(rawAmount);
        if (amount == null || amount.signum() <= 0) return denied("Withdraw a positive currency amount.");
        if (!economy.enabled()) return denied("Money features are disabled.");
        if (!cache.ready()) return denied("Civilization authorization data is unavailable. Try again after it reloads.");
        Member member = cache.snapshot().member(actorId);
        if (member == null || member.role() != Role.LEADER) return denied("Only the leader may withdraw treasury money.");
        UUID operationId = UUID.randomUUID();
        return database.transaction(connection -> locks.withLock(member.civilizationId(), () -> {
            BigDecimal prior = lockTreasury(connection, member.civilizationId());
            // The cache is only a preflight hint. Keep the civilization and membership
            // locks through the debit so a leadership transfer cannot race authorization.
            try (PreparedStatement statement = Sql.prepare(connection, """
                SELECT c.leader_uuid, m.role FROM civilizations c
                JOIN civ_members m ON m.civ_id = c.id AND m.player_uuid = ?
                WHERE c.id = ? AND c.status = 'ACTIVE' FOR UPDATE
                """, actorId, member.civilizationId()); ResultSet result = statement.executeQuery()) {
                if (!result.next() || !actorId.equals(UuidBytes.get(result, "leader_uuid"))
                    || !"LEADER".equals(result.getString("role"))) {
                    return OperationResult.denied("Only the current civilization leader may withdraw treasury money.");
                }
            }
            UnresolvedWithdrawal unresolved = unresolvedWithdrawal(connection, actorId);
            if (unresolved != null) return OperationResult.denied(
                "A previous treasury withdrawal is unresolved (state " + unresolved.state() + ", operation "
                    + unresolved.id() + "). Reconcile it before starting another payout.");
            if (prior.compareTo(amount) < 0) return OperationResult.denied("The treasury does not contain enough money.");
            BigDecimal resulting = prior.subtract(amount);
            Instant now = clock.instant();
            try (PreparedStatement operation = connection.prepareStatement("""
                INSERT INTO economy_operations(operation_id, operation_type, player_uuid, beneficiary_uuid, civ_id,
                    amount, state, context_json, created_at, updated_at)
                VALUES (?, 'TREASURY_WITHDRAWAL', ?, ?, ?, ?, 'DB_APPLIED', '{}', ?, ?)
                """)) {
                Sql.bind(operation, operationId, actorId, actorId, member.civilizationId(), amount, now, now);
                operation.executeUpdate();
            }
            updateTreasury(connection, member.civilizationId(), resulting);
            insertLedger(connection, operationId, member.civilizationId(), actorId, actorId, amount.negate(), prior,
                resulting, "WITHDRAWAL_RESERVED", null);
            AuditLog.write(connection, member.civilizationId(), actorId, "treasury.withdraw.reserve", "economy_operation",
                operationId.toString(), Map.of("amount", amount.toPlainString()), settings.serverId());
            return OperationResult.ok("Withdrawal reserved.");
        })).thenCompose(stage -> stage.success() ? resumeWithdrawal(operationId)
            : CompletableFuture.completedFuture(stage));
    }

    /** Recovery entry point. Only DB_APPLIED is automatically dispatched. */
    public CompletableFuture<OperationResult> resumeWithdrawal(UUID operationId) {
        return database.transaction(connection -> claimWithdrawalDelivery(connection, operationId))
            .thenCompose(stage -> {
                if (!stage.dispatch()) return CompletableFuture.completedFuture(stage.result());
                WithdrawalOperation operation = stage.operation();
                return economy.port().deposit(operation.id(), operation.beneficiary(), operation.amount())
                    .exceptionally(error -> EconomyPort.Result.ambiguous(rootMessage(error)))
                    .thenCompose(result -> finishWithdrawalDelivery(operation, result));
            });
    }

    private WithdrawalStage claimWithdrawalDelivery(Connection connection, UUID operationId) throws Exception {
        WithdrawalOperation operation = lockWithdrawal(connection, operationId);
        if (operation == null) return WithdrawalStage.done(OperationResult.denied("Unknown treasury withdrawal."));
        return switch (operation.state()) {
            case DB_APPLIED -> {
                changeState(connection, operation.id(), EconomyOperationState.DB_APPLIED,
                    EconomyOperationState.DELIVERY_IN_FLIGHT, "PAYOUT_DISPATCHED");
                yield WithdrawalStage.dispatch(operation);
            }
            case COMPLETED -> WithdrawalStage.done(OperationResult.ok("Treasury withdrawal was already delivered."));
            case FAILED -> WithdrawalStage.done(OperationResult.denied("Treasury withdrawal was already compensated."));
            case DELIVERY_IN_FLIGHT -> WithdrawalStage.done(OperationResult.denied(
                "The payout outcome is ambiguous and requires administrator reconciliation; it will not be replayed automatically."));
            default -> WithdrawalStage.done(OperationResult.denied("Treasury withdrawal cannot run in state "
                + operation.state() + "."));
        };
    }

    private CompletableFuture<OperationResult> finishWithdrawalDelivery(WithdrawalOperation operation,
                                                                         EconomyPort.Result external) {
        if (external.ambiguous()) {
            return recordAmbiguous(operation.id(), EconomyOperationState.DELIVERY_IN_FLIGHT,
                    external.providerMessage())
                .thenApply(ignored -> OperationResult.denied(
                    "The payout outcome is unknown and requires administrator reconciliation."));
        }
        return database.transaction(connection -> finalizeWithdrawal(connection, operation, external))
            .thenCompose(result -> cache.refreshAfterMutation().handle((ignored, refreshFailure) -> refreshFailure == null
                ? result : new OperationResult(result.success(), result.message()
                    + " Cached balances remain unavailable until the cache reload succeeds.")));
    }

    private OperationResult finalizeWithdrawal(Connection connection, WithdrawalOperation operation,
                                               EconomyPort.Result external) throws Exception {
        WithdrawalOperation locked = lockWithdrawal(connection, operation.id());
        if (locked == null || locked.state() != EconomyOperationState.DELIVERY_IN_FLIGHT) {
            throw new IllegalStateException("Treasury withdrawal state changed before provider result was recorded");
        }
        if (external.success()) {
            changeState(connection, operation.id(), EconomyOperationState.DELIVERY_IN_FLIGHT,
                EconomyOperationState.COMPLETED, external.providerMessage());
            AuditLog.write(connection, operation.civilizationId(), operation.player(), "treasury.withdraw.complete",
                "economy_operation", operation.id().toString(), Map.of("amount", operation.amount().toPlainString()),
                settings.serverId());
            return OperationResult.ok("Withdrew " + operation.amount().toPlainString() + " from the treasury.");
        }

        BigDecimal current = lockTreasuryOrNull(connection, operation.civilizationId());
        if (current == null) {
            // The provider definitively rejected the payout, but a disband may
            // already have archived the treasury. Keep the reserved payout
            // retryable instead of crediting a dead treasury.
            changeState(connection, operation.id(), EconomyOperationState.DELIVERY_IN_FLIGHT,
                EconomyOperationState.DB_APPLIED, "PAYOUT_RETRY:" + external.providerMessage());
            return OperationResult.denied("The payout was rejected and remains queued for retry.");
        }
        BigDecimal restored = current.add(operation.amount());
        updateTreasury(connection, operation.civilizationId(), restored);
        insertLedger(connection, UUID.randomUUID(), operation.civilizationId(), operation.player(), operation.beneficiary(),
            operation.amount(), current, restored, "WITHDRAWAL_COMPENSATED", operation.id().toString());
        changeState(connection, operation.id(), EconomyOperationState.DELIVERY_IN_FLIGHT,
            EconomyOperationState.FAILED, external.providerMessage());
        AuditLog.write(connection, operation.civilizationId(), operation.player(), "treasury.withdraw.compensated",
            "economy_operation", operation.id().toString(), Map.of("reason", external.providerMessage()), settings.serverId());
        return OperationResult.denied("The economy provider rejected the payout; the treasury reservation was restored.");
    }

    public CompletableFuture<List<Entry>> history(UUID viewer, int page) {
        Member member = cache.snapshot().member(viewer);
        if (member == null || !database.healthy()) return CompletableFuture.completedFuture(List.of());
        int safePage = Math.max(1, page);
        return database.read(connection -> {
            List<Entry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, actor_uuid, beneficiary_uuid, amount, prior_balance, resulting_balance, reason, created_at
                FROM treasury_ledger WHERE civ_id = ? ORDER BY created_at DESC LIMIT 20 OFFSET ?
                """)) {
                statement.setLong(1, member.civilizationId());
                statement.setInt(2, (safePage - 1) * 20);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) entries.add(new Entry(UuidBytes.fromBytes(result.getBytes(1)),
                        UuidBytes.fromBytes(result.getBytes(2)), UuidBytes.fromBytes(result.getBytes(3)),
                        result.getBigDecimal(4), result.getBigDecimal(5), result.getBigDecimal(6), result.getString(7),
                        TimeUtil.instant(result.getTimestamp(8))));
                }
            }
            return List.copyOf(entries);
        });
    }

    private DepositOperation loadDeposit(Connection connection, UUID operationId) throws Exception {
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT operation_id, player_uuid, civ_id, amount, state, provider_response FROM economy_operations
            WHERE operation_id = ? AND operation_type = 'TREASURY_DEPOSIT'
            """, operationId); ResultSet result = statement.executeQuery()) {
            return result.next() ? depositOperation(result) : null;
        }
    }

    private DepositOperation lockDeposit(Connection connection, UUID operationId) throws Exception {
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT operation_id, player_uuid, civ_id, amount, state, provider_response
            FROM economy_operations WHERE operation_id = ? AND operation_type = 'TREASURY_DEPOSIT' FOR UPDATE
            """, operationId); ResultSet result = statement.executeQuery()) {
            return result.next() ? depositOperation(result) : null;
        }
    }

    private DepositOperation depositOperation(ResultSet result) throws Exception {
        long rawCiv = result.getLong("civ_id");
        Long civilizationId = result.wasNull() ? null : rawCiv;
        return new DepositOperation(UuidBytes.fromBytes(result.getBytes("operation_id")),
            UuidBytes.fromBytes(result.getBytes("player_uuid")), civilizationId, result.getBigDecimal("amount"),
            EconomyOperationState.valueOf(result.getString("state")),
            Objects.toString(result.getString("provider_response"), ""));
    }

    private WithdrawalOperation lockWithdrawal(Connection connection, UUID operationId) throws Exception {
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT operation_id, player_uuid, beneficiary_uuid, civ_id, amount, state, provider_response
            FROM economy_operations WHERE operation_id = ? AND operation_type = 'TREASURY_WITHDRAWAL' FOR UPDATE
            """, operationId); ResultSet result = statement.executeQuery()) {
            if (!result.next()) return null;
            UUID player = UuidBytes.fromBytes(result.getBytes("player_uuid"));
            UUID beneficiary = UuidBytes.fromBytes(result.getBytes("beneficiary_uuid"));
            return new WithdrawalOperation(UuidBytes.fromBytes(result.getBytes("operation_id")), player,
                beneficiary == null ? player : beneficiary, result.getLong("civ_id"), result.getBigDecimal("amount"),
                EconomyOperationState.valueOf(result.getString("state")),
                Objects.toString(result.getString("provider_response"), ""));
        }
    }

    private UnresolvedWithdrawal unresolvedWithdrawal(Connection connection, UUID playerId) throws Exception {
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT operation_id, state FROM economy_operations
            WHERE operation_type = 'TREASURY_WITHDRAWAL' AND player_uuid = ?
              AND state NOT IN ('COMPLETED','FAILED')
            ORDER BY created_at LIMIT 1 FOR UPDATE
            """, playerId); ResultSet result = statement.executeQuery()) {
            return result.next() ? new UnresolvedWithdrawal(UuidBytes.fromBytes(result.getBytes(1)),
                EconomyOperationState.valueOf(result.getString(2))) : null;
        }
    }

    private BigDecimal lockTreasury(Connection connection, long civilizationId) throws Exception {
        BigDecimal balance = lockTreasuryOrNull(connection, civilizationId);
        if (balance == null) throw new IllegalStateException("Civilization is not active");
        return balance;
    }

    private BigDecimal lockTreasuryOrNull(Connection connection, long civilizationId) throws Exception {
        try (PreparedStatement statement = Sql.prepare(connection,
            "SELECT treasury_balance FROM civilizations WHERE id = ? AND status = 'ACTIVE' FOR UPDATE", civilizationId);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getBigDecimal(1) : null;
        }
    }

    private void updateTreasury(Connection connection, long civilizationId, BigDecimal balance) throws Exception {
        if (balance.signum() < 0) throw new IllegalStateException("Negative treasury balance");
        try (PreparedStatement statement = Sql.prepare(connection,
            "UPDATE civilizations SET treasury_balance = ?, row_version = row_version + 1 WHERE id = ?",
            balance, civilizationId)) {
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Civilization treasury disappeared");
        }
    }

    private void insertLedger(Connection connection, UUID operationId, long civilizationId, UUID actor, UUID beneficiary,
                              BigDecimal amount, BigDecimal prior, BigDecimal resulting, String reason,
                              String relatedId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO treasury_ledger(operation_id, civ_id, actor_uuid, beneficiary_uuid, amount, prior_balance,
                resulting_balance, reason, related_type, related_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ECONOMY_OPERATION', ?, ?)
            """)) {
            Sql.bind(statement, operationId, civilizationId, actor, beneficiary, amount, prior, resulting, reason,
                relatedId, clock.instant());
            statement.executeUpdate();
        }
    }

    private void changeState(Connection connection, UUID id, EconomyOperationState expected,
                             EconomyOperationState next, String response) throws Exception {
        try (PreparedStatement statement = Sql.prepare(connection, """
            UPDATE economy_operations SET state = ?, provider_response = ?, retry_count = retry_count + 1,
                updated_at = ? WHERE operation_id = ? AND state = ?
            """, next, truncate(response), clock.instant(), id, expected)) {
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Economy operation state changed concurrently");
        }
    }

    private CompletableFuture<Void> recordAmbiguous(UUID id, EconomyOperationState expected, String response) {
        return database.transaction(connection -> {
            try (PreparedStatement statement = Sql.prepare(connection, """
                UPDATE economy_operations SET provider_response = ?, retry_count = retry_count + 1, updated_at = ?
                WHERE operation_id = ? AND state = ?
                """, truncate(response), clock.instant(), id, expected)) {
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static BigDecimal normalize(BigDecimal amount) {
        if (amount == null) return null;
        try { return amount.setScale(2, RoundingMode.HALF_UP); }
        catch (ArithmeticException ignored) { return null; }
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

    private static CompletableFuture<OperationResult> denied(String message) {
        return CompletableFuture.completedFuture(OperationResult.denied(message));
    }

    private record DepositOperation(UUID id, UUID player, Long civilizationId, BigDecimal amount,
                                    EconomyOperationState state, String providerResponse) {
        EconomyService.Charge charge() {
            return new EconomyService.Charge(id, amount, OperationResult.ok("Currency was reserved."));
        }
    }

    private record DepositStage(OperationResult result, EconomyService.Charge refund, UUID player, boolean mutated) {
        static DepositStage done(OperationResult result) { return new DepositStage(result, null, null, false); }
        static DepositStage mutated(OperationResult result) { return new DepositStage(result, null, null, true); }
        static DepositStage refund(OperationResult result, DepositOperation operation) {
            return new DepositStage(result, operation.charge(), operation.player(), false);
        }
    }

    private record WithdrawalOperation(UUID id, UUID player, UUID beneficiary, long civilizationId, BigDecimal amount,
                                       EconomyOperationState state, String providerResponse) {}

    private record UnresolvedWithdrawal(UUID id, EconomyOperationState state) {}

    private record WithdrawalStage(OperationResult result, WithdrawalOperation operation, boolean dispatch) {
        static WithdrawalStage done(OperationResult result) { return new WithdrawalStage(result, null, false); }
        static WithdrawalStage dispatch(WithdrawalOperation operation) { return new WithdrawalStage(null, operation, true); }
    }

    public record Entry(UUID operationId, UUID actor, UUID beneficiary, BigDecimal amount, BigDecimal prior,
                        BigDecimal resulting, String reason, Instant createdAt) {}
}
