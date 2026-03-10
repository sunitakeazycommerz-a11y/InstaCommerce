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
}
