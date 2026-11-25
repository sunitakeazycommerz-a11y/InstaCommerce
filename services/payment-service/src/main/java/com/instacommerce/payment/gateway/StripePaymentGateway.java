package com.instacommerce.payment.gateway;

import com.instacommerce.payment.exception.PaymentGatewayException;
import com.instacommerce.payment.exception.PaymentGatewayTransientException;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import java.util.Collections;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test & !local")
public class StripePaymentGateway implements PaymentGateway {
    private static final String LOCAL_PLACEHOLDER_KEY = "sk_test_local_placeholder";
    private final String apiKey;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final boolean allowPlaceholderFallback;
    private final MockPaymentGateway localFallback = new MockPaymentGateway();

    public StripePaymentGateway(String apiKey, int connectTimeoutMs, int readTimeoutMs) {
        this(apiKey, connectTimeoutMs, readTimeoutMs, false);
    }

    @Autowired
    public StripePaymentGateway(@Value("${stripe.api-key:}") String apiKey,
                                @Value("${stripe.connect-timeout-ms:5000}") int connectTimeoutMs,
                                @Value("${stripe.read-timeout-ms:15000}") int readTimeoutMs,
                                @Value("${payment.gateway.allow-placeholder-fallback:false}") boolean allowPlaceholderFallback) {
        this.apiKey = apiKey;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.allowPlaceholderFallback = allowPlaceholderFallback;
    }

    @Override
    public GatewayAuthResult authorize(GatewayAuthRequest request) {
        if (usePlaceholderFallback()) {
            return localFallback.authorize(request);
        }
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
        if (usePlaceholderFallback()) {
            return localFallback.capture(pspReference, amountCents, idempotencyKey);
        }
        ensureApiKey();
        RequestOptions retrieveOptions = buildOptions(null);
        RequestOptions captureOptions = buildOptions(idempotencyKey);
        try {
            PaymentIntent intent = PaymentIntent.retrieve(pspReference, retrieveOptions);
            PaymentIntentCaptureParams params = PaymentIntentCaptureParams.builder()
                .setAmountToCapture(amountCents)
                .build();
            intent.capture(params, captureOptions);
            return GatewayCaptureResult.ok();
        } catch (CardException ex) {
            String declineCode = ex.getDeclineCode();
            return GatewayCaptureResult.failure(declineCode != null ? declineCode : "capture_failed");
        } catch (StripeException ex) {
            throw toGatewayException(ex);
        }
    }

    @Override
    public GatewayVoidResult voidAuth(String pspReference, String idempotencyKey) {
        if (usePlaceholderFallback()) {
            return localFallback.voidAuth(pspReference, idempotencyKey);
        }
        ensureApiKey();
        RequestOptions retrieveOptions = buildOptions(null);
        RequestOptions cancelOptions = buildOptions(idempotencyKey);
        try {
            PaymentIntent intent = PaymentIntent.retrieve(pspReference, retrieveOptions);
            intent.cancel(Collections.emptyMap(), cancelOptions);
            return GatewayVoidResult.ok();
        } catch (StripeException ex) {
            throw toGatewayException(ex);
        }
    }

    @Override
    public GatewayStatusResult getStatus(String pspReference) {
        if (usePlaceholderFallback()) {
            return localFallback.getStatus(pspReference);
        }
        ensureApiKey();
        try {
            RequestOptions options = buildOptions(null);
            PaymentIntent intent = PaymentIntent.retrieve(pspReference, options);
            GatewayStatusResult.PspPaymentState state = mapStripeStatus(intent.getStatus());
            return GatewayStatusResult.of(state, intent.getStatus(), intent.getAmountReceived());
        } catch (StripeException ex) {
            throw toGatewayException(ex);
        }
    }

    @Override
    public GatewayRefundStatusResult getRefundStatus(String pspRefundId) {
        if (usePlaceholderFallback()) {
            return localFallback.getRefundStatus(pspRefundId);
        }
        ensureApiKey();
        try {
            RequestOptions options = buildOptions(null);
            Refund refund = Refund.retrieve(pspRefundId, options);
            GatewayRefundStatusResult.PspRefundState state = mapStripeRefundStatus(refund.getStatus());
            return GatewayRefundStatusResult.of(state, refund.getStatus(), refund.getAmount());
        } catch (StripeException ex) {
            throw toGatewayException(ex);
        }
    }

    @Override
    public GatewayRefundResult refund(String pspReference, long amountCents, String idempotencyKey, UUID internalRefundId) {
        if (usePlaceholderFallback()) {
            return localFallback.refund(pspReference, amountCents, idempotencyKey, internalRefundId);
        }
        ensureApiKey();
        try {
            RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(pspReference)
                .setAmount(amountCents)
                .putMetadata("internalRefundId", internalRefundId.toString())
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

    private GatewayStatusResult.PspPaymentState mapStripeStatus(String stripeStatus) {
        if (stripeStatus == null) {
            return GatewayStatusResult.PspPaymentState.UNKNOWN;
        }
        return switch (stripeStatus) {
            case "requires_payment_method" -> GatewayStatusResult.PspPaymentState.REQUIRES_PAYMENT_METHOD;
            case "requires_confirmation" -> GatewayStatusResult.PspPaymentState.REQUIRES_CONFIRMATION;
            case "requires_action" -> GatewayStatusResult.PspPaymentState.REQUIRES_ACTION;
            case "processing" -> GatewayStatusResult.PspPaymentState.PROCESSING;
            case "requires_capture" -> GatewayStatusResult.PspPaymentState.REQUIRES_CAPTURE;
            case "succeeded" -> GatewayStatusResult.PspPaymentState.SUCCEEDED;
            case "canceled" -> GatewayStatusResult.PspPaymentState.CANCELED;
            default -> GatewayStatusResult.PspPaymentState.UNKNOWN;
        };
    }

    private GatewayRefundStatusResult.PspRefundState mapStripeRefundStatus(String stripeStatus) {
        if (stripeStatus == null) {
            return GatewayRefundStatusResult.PspRefundState.UNKNOWN;
        }
        return switch (stripeStatus) {
            case "pending" -> GatewayRefundStatusResult.PspRefundState.PENDING;
            case "succeeded" -> GatewayRefundStatusResult.PspRefundState.SUCCEEDED;
            case "failed" -> GatewayRefundStatusResult.PspRefundState.FAILED;
            case "canceled" -> GatewayRefundStatusResult.PspRefundState.CANCELED;
            case "requires_action" -> GatewayRefundStatusResult.PspRefundState.REQUIRES_ACTION;
            default -> GatewayRefundStatusResult.PspRefundState.UNKNOWN;
        };
    }

    private boolean isAuthorized(String status) {
        return "requires_capture".equals(status)
            || "succeeded".equals(status)
            || "processing".equals(status);
    }

    private boolean usePlaceholderFallback() {
        return allowPlaceholderFallback && LOCAL_PLACEHOLDER_KEY.equals(apiKey);
    }

    private void ensureApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new PaymentGatewayException("Stripe API key is not configured");
        }
    }

    private PaymentGatewayException toGatewayException(StripeException ex) {
        if (ex instanceof ApiConnectionException) {
            return new PaymentGatewayTransientException(
                "PSP communication failure: " + ex.getMessage(), ex);
        }
        String code = ex.getCode();
        String message = (code == null || code.isBlank())
            ? "Payment processing failed"
            : "Payment processing failed (stripe_code=" + code + ")";
        return new PaymentGatewayException(message, ex);
    }
}
