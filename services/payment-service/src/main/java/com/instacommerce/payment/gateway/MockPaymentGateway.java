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
        return GatewayCaptureResult.success();
    }

    @Override
    public GatewayVoidResult voidAuth(String pspReference, String idempotencyKey) {
        return GatewayVoidResult.success();
    }

    @Override
    public GatewayRefundResult refund(String pspReference, long amountCents, String idempotencyKey) {
        return GatewayRefundResult.success("mock_re_" + UUID.randomUUID());
    }
}
