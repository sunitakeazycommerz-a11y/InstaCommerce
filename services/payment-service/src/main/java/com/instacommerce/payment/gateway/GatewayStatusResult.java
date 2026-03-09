package com.instacommerce.payment.gateway;

/**
 * Read-only snapshot of a payment's current state at the PSP.
 * Used by the stale-pending recovery job to reconcile local state
 * without reissuing gateway mutations.
 */
public record GatewayStatusResult(
    PspPaymentState state,
    String rawStatus,
    Long amountCapturedCents
) {

    /**
     * Normalized PSP payment states mapped from provider-specific status strings.
     * Stripe PaymentIntent statuses map as follows:
     * <ul>
     *   <li>{@code requires_payment_method} → {@link #REQUIRES_PAYMENT_METHOD}</li>
     *   <li>{@code requires_confirmation}   → {@link #REQUIRES_CONFIRMATION}</li>
     *   <li>{@code requires_action}         → {@link #REQUIRES_ACTION}</li>
     *   <li>{@code processing}              → {@link #PROCESSING}</li>
     *   <li>{@code requires_capture}        → {@link #REQUIRES_CAPTURE} (authorized)</li>
     *   <li>{@code succeeded}               → {@link #SUCCEEDED} (captured)</li>
     *   <li>{@code canceled}                → {@link #CANCELED} (voided)</li>
     * </ul>
     */
    public enum PspPaymentState {
        REQUIRES_PAYMENT_METHOD,
        REQUIRES_CONFIRMATION,
        REQUIRES_ACTION,
        PROCESSING,
        REQUIRES_CAPTURE,
        SUCCEEDED,
        CANCELED,
        UNKNOWN
    }

    public boolean isAuthorized() {
        return state == PspPaymentState.REQUIRES_CAPTURE;
    }

    public boolean isCaptured() {
        return state == PspPaymentState.SUCCEEDED;
    }

    public boolean isCanceled() {
        return state == PspPaymentState.CANCELED;
    }

    public boolean isInFlight() {
        return state == PspPaymentState.PROCESSING;
    }

    public boolean isTerminal() {
        return state == PspPaymentState.SUCCEEDED
            || state == PspPaymentState.CANCELED;
    }

    public boolean isFailed() {
        return state == PspPaymentState.REQUIRES_PAYMENT_METHOD
            || state == PspPaymentState.REQUIRES_CONFIRMATION
            || state == PspPaymentState.REQUIRES_ACTION;
    }

    public static GatewayStatusResult of(PspPaymentState state, String rawStatus, Long amountCapturedCents) {
        return new GatewayStatusResult(state, rawStatus, amountCapturedCents);
    }
}
