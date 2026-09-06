package io.github.empireage.civilizations.service.economy;

/**
 * Durable phases for an operation that crosses MySQL and a Vault provider.
 *
 * <p>The three {@code *_IN_FLIGHT} states are deliberately terminal for
 * automatic recovery. Vault has no transaction lookup or durable idempotency
 * key, so after a process dies in one of those states the plugin cannot know
 * whether the provider applied the transfer. Replaying it would risk charging
 * or paying twice; an administrator must reconcile the provider ledger first.</p>
 */
public enum EconomyOperationState {
    /** Persisted intent; no provider call has begun. */
    PENDING,
    /** A withdrawal may have reached Vault. Never automatically replay after restart. */
    WITHDRAWAL_IN_FLIGHT,
    /** Vault confirmed a withdrawal and the domain transaction is pending. */
    EXTERNAL_APPLIED,
    /** The domain transaction committed and an outbound payment has not begun. */
    DB_APPLIED,
    /** A payout may have reached Vault. Never automatically replay after restart. */
    DELIVERY_IN_FLIGHT,
    /** A payout or refund was definitively rejected and is safe to retry. */
    COMPENSATION_PENDING,
    /** A refund may have reached Vault. Never automatically replay after restart. */
    REFUND_IN_FLIGHT,
    /** A payout was persisted but has not begun. */
    PENDING_DELIVERY,
    COMPLETED,
    FAILED;

    public boolean ambiguous() {
        return this == WITHDRAWAL_IN_FLIGHT || this == DELIVERY_IN_FLIGHT || this == REFUND_IN_FLIGHT;
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED;
    }

    /** Nonterminal plot operations must retain their claim foreign key. */
    public boolean retainsDomainReference() {
        return !terminal();
    }
}
