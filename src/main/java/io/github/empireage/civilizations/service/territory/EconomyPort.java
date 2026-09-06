package io.github.empireage.civilizations.service.territory;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Adapter boundary for Vault (or another economy). Implementations must treat the
 * operation id plus operation kind as an idempotency key.
 */
public interface EconomyPort {
    default boolean available() { return true; }

    CompletableFuture<Result> withdraw(UUID operationId, UUID account, BigDecimal amount);

    /** A tax withdrawal may report verified insufficient funds separately from provider errors. */
    default CompletableFuture<Result> withdrawTax(UUID operationId, UUID account, BigDecimal amount) {
        return withdraw(operationId, account, amount);
    }

    CompletableFuture<Result> deposit(UUID operationId, UUID account, BigDecimal amount);

    /** Refunds a prior withdrawal. It must also be idempotent. */
    CompletableFuture<Result> refund(UUID operationId, UUID account, BigDecimal amount);

    record Result(boolean success, String providerMessage, boolean verifiedInsufficientFunds) {
        private static final String AMBIGUOUS_PREFIX = "AMBIGUOUS:";
        public static final String INSUFFICIENT_FUNDS_PREFIX = "INSUFFICIENT_FUNDS:";

        public Result(boolean success, String providerMessage) {
            this(success, providerMessage, false);
        }

        public static Result insufficientFunds() {
            return new Result(false, "Account balance is below the weekly plot tax.", true);
        }

        public static Result ok(String message) {
            return new Result(true, message == null ? "" : message);
        }

        public static Result failed(String message) {
            return new Result(false, message == null ? "Economy operation failed" : message);
        }

        /**
         * The provider invocation failed in a way that does not prove whether
         * money moved. Callers must retain their in-flight marker and require
         * reconciliation instead of replaying the transfer.
         */
        public static Result ambiguous(String message) {
            return new Result(false, AMBIGUOUS_PREFIX + " "
                + (message == null || message.isBlank() ? "provider outcome is unknown" : message));
        }

        public boolean ambiguous() {
            return !success && providerMessage != null && providerMessage.startsWith(AMBIGUOUS_PREFIX);
        }
    }

    EconomyPort DISABLED = new EconomyPort() {
        @Override
        public boolean available() { return false; }

        @Override
        public CompletableFuture<Result> withdraw(UUID operationId, UUID account, BigDecimal amount) {
            return CompletableFuture.completedFuture(disabled(amount));
        }

        @Override
        public CompletableFuture<Result> deposit(UUID operationId, UUID account, BigDecimal amount) {
            return CompletableFuture.completedFuture(disabled(amount));
        }

        @Override
        public CompletableFuture<Result> refund(UUID operationId, UUID account, BigDecimal amount) {
            return CompletableFuture.completedFuture(disabled(amount));
        }

        private Result disabled(BigDecimal amount) {
            return amount == null || amount.signum() == 0 ? Result.ok("No currency transfer required")
                : Result.failed("No economy provider is available");
        }
    };
}
