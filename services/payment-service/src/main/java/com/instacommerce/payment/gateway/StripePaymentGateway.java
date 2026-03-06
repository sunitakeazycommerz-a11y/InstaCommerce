package com.instacommerce.payment.gateway;

import com.instacommerce.payment.exception.PaymentGatewayException;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class StripePaymentGateway implements PaymentGateway {
    private final String apiKey;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public StripePaymentGateway(@Value("${stripe.api-key:}") String apiKey,
                                @Value("${stripe.connect-timeout-ms:5000}") int connectTimeoutMs,
                                @Value("${stripe.read-timeout-ms:15000}") int readTimeoutMs) {
        this.apiKey = apiKey;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public GatewayAuthResult authorize(GatewayAuthRequest request) {
        ensureApiKey();
        try {
            PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
                .setAmount(request.amountCents())
                .setCurrency(request.currency().toLowerCase())
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL);
            if (request.paymentMethod() != null && !request.paymentMethod().isBlank()) {
                builder.setPaymentMethod(request.paymentMethod());
                builder.setConfirm(true);
            }
            PaymentIntentCreateParams params = builder.build();
            RequestOptions options = buildOptions(request.idempotencyKey());
            PaymentIntent intent = PaymentIntent.create(params, options);
            if (isAuthorized(intent.getStatus())) {
                return GatewayAuthResult.success(intent.getId());
            }
            return GatewayAuthResult.declined(intent.getStatus());
        } catch (CardException ex) {
            String declineCode = ex.getDeclineCode();
            return GatewayAuthResult.declined(declineCode != null ? declineCode : "card_declined");
        } catch (StripeException ex) {
            throw toGatewayException(ex);
        }
    }

    @Override
    public GatewayCaptureResult capture(String pspReference, long amountCents, String idempotencyKey) {
        ensureApiKey();
        RequestOptions options = buildOptions(idempotencyKey);
        try {
            PaymentIntent intent = PaymentIntent.retrieve(pspReference, options);
            PaymentIntentCaptureParams params = PaymentIntentCaptureParams.builder()
                .setAmountToCapture(amountCents)
                .build();
            intent.capture(params, options);
            return GatewayCaptureResult.success();
        } catch (CardException ex) {
            String declineCode = ex.getDeclineCode();
            return GatewayCaptureResult.failure(declineCode != null ? declineCode : "capture_failed");
        } catch (StripeException ex) {
            throw toGatewayException(ex);
        }
    }

    @Override
    public GatewayVoidResult voidAuth(String pspReference, String idempotencyKey) {
        ensureApiKey();
        RequestOptions options = buildOptions(idempotencyKey);
        try {
            PaymentIntent intent = PaymentIntent.retrieve(pspReference, options);
            intent.cancel(java.util.Collections.emptyMap(), options);
            return GatewayVoidResult.success();
        } catch (StripeException ex) {
            throw toGatewayException(ex);
        }
    }

    @Override
    public GatewayRefundResult refund(String pspReference, long amountCents, String idempotencyKey) {
        ensureApiKey();
        try {
            RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(pspReference)
                .setAmount(amountCents)
                .build();
            RequestOptions options = buildOptions(idempotencyKey);
            Refund refund = Refund.create(params, options);
            return GatewayRefundResult.success(refund.getId());
        } catch (StripeException ex) {
            throw toGatewayException(ex);
        }
    }

    private RequestOptions buildOptions(String idempotencyKey) {
        RequestOptions.RequestOptionsBuilder builder = RequestOptions.builder()
            .setApiKey(apiKey)
            .setConnectTimeout(connectTimeoutMs)
            .setReadTimeout(readTimeoutMs);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            builder.setIdempotencyKey(idempotencyKey);
        }
        return builder.build();
    }

    private boolean isAuthorized(String status) {
        return "requires_capture".equals(status)
            || "succeeded".equals(status)
            || "processing".equals(status);
    }

    private void ensureApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new PaymentGatewayException("Stripe API key is not configured");
        }
    }

    private PaymentGatewayException toGatewayException(StripeException ex) {
        String code = ex.getCode();
        String message = (code == null || code.isBlank())
            ? "Payment processing failed"
            : "Payment processing failed (stripe_code=" + code + ")";
        return new PaymentGatewayException(message, ex);
    }
}
