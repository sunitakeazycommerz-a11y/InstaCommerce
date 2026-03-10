package com.instacommerce.payment.gateway;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class MockPaymentGateway implements PaymentGateway {
    @Override
    public GatewayAuthResult authorize(GatewayAuthRequest request) {
        return GatewayAuthResult.success("mock_pi_" + UUID.randomUUID());
    }

    @Override
    public GatewayCaptureResult capture(String pspReference, long amountCents, String idempotencyKey) {
        return GatewayCaptureResult.ok();
    }

    @Override
    public GatewayVoidResult voidAuth(String pspReference, String idempotencyKey) {
        return GatewayVoidResult.ok();
    }

    @Override
    public GatewayRefundResult refund(String pspReference, long amountCents, String idempotencyKey, UUID internalRefundId) {
        return GatewayRefundResult.success("mock_re_" + UUID.randomUUID());
    }

    @Override
    public GatewayStatusResult getStatus(String pspReference) {
        return GatewayStatusResult.of(
            GatewayStatusResult.PspPaymentState.REQUIRES_CAPTURE,
            "requires_capture",
            0L
        );
    }

    @Override
    public GatewayRefundStatusResult getRefundStatus(String pspRefundId) {
        return GatewayRefundStatusResult.of(
            GatewayRefundStatusResult.PspRefundState.SUCCEEDED,
            "succeeded",
            0L
        );
    }
}
