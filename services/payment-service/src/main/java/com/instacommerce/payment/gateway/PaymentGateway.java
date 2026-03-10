package com.instacommerce.payment.gateway;

import java.util.UUID;

public interface PaymentGateway {
    GatewayAuthResult authorize(GatewayAuthRequest request);

    GatewayCaptureResult capture(String pspReference, long amountCents, String idempotencyKey);

    GatewayVoidResult voidAuth(String pspReference, String idempotencyKey);

    GatewayRefundResult refund(String pspReference, long amountCents, String idempotencyKey, UUID internalRefundId);

    /**
     * Retrieves the current payment state from the PSP without issuing any mutations.
     * Used by the stale-pending recovery job to reconcile local state with PSP truth.
     */
    GatewayStatusResult getStatus(String pspReference);

    /**
     * Retrieves the current refund state from the PSP without issuing any mutations.
     * Used by the refund recovery job to reconcile stale local refund records with PSP truth.
     *
     * @param pspRefundId the PSP-assigned refund identifier (e.g. Stripe {@code re_xxx})
     * @return a read-only snapshot of the refund's current state at the PSP
     */
    GatewayRefundStatusResult getRefundStatus(String pspRefundId);
}
