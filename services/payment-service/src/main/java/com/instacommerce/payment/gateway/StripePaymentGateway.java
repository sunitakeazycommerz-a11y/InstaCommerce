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

    public StripePaymentGateway(@Value("${stripe.api-key:}") String apiKey) {
        this.apiKey = apiKey;
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
            RequestOptions options = RequestOptions.builder()
                .setApiKey(apiKey)
                .setIdempotencyKey(request.idempotencyKey())
                .build();
            PaymentIntent intent = PaymentIntent.create(params, options);
            if (isAuthorized(intent.getStatus())) {
                return GatewayAuthResult.success(intent.getId());
            }
            return GatewayAuthResult.declined(intent.getStatus());
        } catch (CardException ex) {
            String declineCode = ex.getDeclineCode();
            return GatewayAuthResult.declined(declineCode != null ? declineCode : "card_declined");
        } catch (StripeException ex) {
            throw new PaymentGatewayException("Payment processing failed");
        }
    }

    @Override
    public GatewayCaptureResult capture(String pspReference, long amountCents) {
        ensureApiKey();
        RequestOptions options = buildOptions();
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
            throw new PaymentGatewayException("Payment processing failed");
        }
    }

    @Override
    public GatewayVoidResult voidAuth(String pspReference) {
        ensureApiKey();
        RequestOptions options = buildOptions();
        try {
            PaymentIntent intent = PaymentIntent.retrieve(pspReference, options);
            intent.cancel(java.util.Collections.emptyMap(), options);
            return GatewayVoidResult.success();
        } catch (StripeException ex) {
            throw new PaymentGatewayException("Payment processing failed");
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
            RequestOptions options = RequestOptions.builder()
                .setApiKey(apiKey)
                .setIdempotencyKey(idempotencyKey)
                .build();
            Refund refund = Refund.create(params, options);
            return GatewayRefundResult.success(refund.getId());
        } catch (StripeException ex) {
            throw new PaymentGatewayException("Payment processing failed");
        }
    }

    private RequestOptions buildOptions() {
        return RequestOptions.builder().setApiKey(apiKey).build();
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
}
