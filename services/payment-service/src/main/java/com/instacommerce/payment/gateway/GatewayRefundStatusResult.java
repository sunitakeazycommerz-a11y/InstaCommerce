package com.instacommerce.payment.gateway;

/**
 * Read-only snapshot of a refund's current state at the PSP.
 * Used by the refund recovery job to reconcile stale local refund records
 * without reissuing gateway mutations.
 */
public record GatewayRefundStatusResult(
    PspRefundState state,
    String rawStatus,
    Long amountRefundedCents
) {

    /**
     * Normalized PSP refund states mapped from provider-specific status strings.
     * Stripe Refund statuses map as follows:
     * <ul>
     *   <li>{@code pending}          → {@link #PENDING}</li>
     *   <li>{@code succeeded}        → {@link #SUCCEEDED}</li>
     *   <li>{@code failed}           → {@link #FAILED}</li>
     *   <li>{@code canceled}         → {@link #CANCELED}</li>
     *   <li>{@code requires_action}  → {@link #REQUIRES_ACTION}</li>
     * </ul>
     */
    public enum PspRefundState {
        PENDING,
        SUCCEEDED,
        FAILED,
        CANCELED,
        REQUIRES_ACTION,
        UNKNOWN
    }

    public boolean isSucceeded() {
        return state == PspRefundState.SUCCEEDED;
    }

    public boolean isFailed() {
        return state == PspRefundState.FAILED;
    }

    public boolean isPending() {
        return state == PspRefundState.PENDING;
    }

    public boolean isCanceled() {
        return state == PspRefundState.CANCELED;
    }

    public boolean isTerminal() {
        return state == PspRefundState.SUCCEEDED
            || state == PspRefundState.FAILED
            || state == PspRefundState.CANCELED;
    }

    public static GatewayRefundStatusResult of(PspRefundState state, String rawStatus, Long amountRefundedCents) {
        return new GatewayRefundStatusResult(state, rawStatus, amountRefundedCents);
    }
}
