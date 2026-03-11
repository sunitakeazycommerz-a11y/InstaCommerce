package com.instacommerce.payment.gateway;

import com.instacommerce.payment.exception.PaymentGatewayException;
import com.instacommerce.payment.exception.PaymentGatewayUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Resilience wrapper for {@link PaymentGateway} that adds circuit-breaker and retry
 * protection around PSP calls. When the circuit breaker is open, calls are immediately
 * rejected with {@link PaymentGatewayUnavailableException}.
 *
 * <p>Aspect ordering is configured so that Retry (order&nbsp;1) wraps CircuitBreaker
 * (order&nbsp;2), ensuring retries re-enter the CB on each attempt.</p>
 *
 * <p>Timeout enforcement is delegated to the Stripe SDK's own connect/read timeouts
 * (configured via {@code stripe.connect-timeout-ms} and {@code stripe.read-timeout-ms})
 * rather than {@code @TimeLimiter}, because all gateway methods are synchronous.
 * The {@code resilience4j.timelimiter} YAML config is present for documentation and
 * can be activated if the gateway is migrated to async return types in the future.</p>
 */
@Component
@Primary
@Profile("!test")
public class ResilientPaymentGatewayDecorator implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(ResilientPaymentGatewayDecorator.class);
    private static final String BACKEND = "paymentGateway";

    private final PaymentGateway delegate;

    public ResilientPaymentGatewayDecorator(
            @Qualifier("stripePaymentGateway") PaymentGateway delegate,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.delegate = delegate;
        circuitBreakerRegistry.circuitBreaker(BACKEND)
            .getEventPublisher()
            .onStateTransition(event ->
                log.warn("Payment gateway circuit breaker state transition: {}",
                    event.getStateTransition()));
    }

    // -----------------------------------------------------------------------
    // Decorated gateway methods
    // -----------------------------------------------------------------------

    @Override
    @CircuitBreaker(name = BACKEND, fallbackMethod = "authorizeFallback")
    @Retry(name = BACKEND)
    public GatewayAuthResult authorize(GatewayAuthRequest request) {
        return delegate.authorize(request);
    }

    @Override
    @CircuitBreaker(name = BACKEND, fallbackMethod = "captureFallback")
    @Retry(name = BACKEND)
    public GatewayCaptureResult capture(String pspReference, long amountCents, String idempotencyKey) {
        return delegate.capture(pspReference, amountCents, idempotencyKey);
    }

    @Override
    @CircuitBreaker(name = BACKEND, fallbackMethod = "voidAuthFallback")
    @Retry(name = BACKEND)
    public GatewayVoidResult voidAuth(String pspReference, String idempotencyKey) {
        return delegate.voidAuth(pspReference, idempotencyKey);
    }

    @Override
    @CircuitBreaker(name = BACKEND, fallbackMethod = "refundFallback")
    @Retry(name = BACKEND)
    public GatewayRefundResult refund(String pspReference, long amountCents,
                                      String idempotencyKey, UUID internalRefundId) {
        return delegate.refund(pspReference, amountCents, idempotencyKey, internalRefundId);
    }

    @Override
    @CircuitBreaker(name = BACKEND, fallbackMethod = "getStatusFallback")
    @Retry(name = BACKEND)
    public GatewayStatusResult getStatus(String pspReference) {
        return delegate.getStatus(pspReference);
    }

    @Override
    @CircuitBreaker(name = BACKEND, fallbackMethod = "getRefundStatusFallback")
    @Retry(name = BACKEND)
    public GatewayRefundStatusResult getRefundStatus(String pspRefundId) {
        return delegate.getRefundStatus(pspRefundId);
    }

    // -----------------------------------------------------------------------
    // Fallback methods — specific variant for CB-open, generic for re-throw
    // -----------------------------------------------------------------------

    private GatewayAuthResult authorizeFallback(GatewayAuthRequest request,
                                                CallNotPermittedException ex) {
        throw gatewayUnavailable(ex);
    }

    private GatewayAuthResult authorizeFallback(GatewayAuthRequest request, Exception ex) {
        throw rethrow(ex);
    }

    private GatewayCaptureResult captureFallback(String pspReference, long amountCents,
                                                 String idempotencyKey,
                                                 CallNotPermittedException ex) {
        throw gatewayUnavailable(ex);
    }

    private GatewayCaptureResult captureFallback(String pspReference, long amountCents,
                                                 String idempotencyKey, Exception ex) {
        throw rethrow(ex);
    }

    private GatewayVoidResult voidAuthFallback(String pspReference, String idempotencyKey,
                                               CallNotPermittedException ex) {
        throw gatewayUnavailable(ex);
    }

    private GatewayVoidResult voidAuthFallback(String pspReference, String idempotencyKey,
                                               Exception ex) {
        throw rethrow(ex);
    }

    private GatewayRefundResult refundFallback(String pspReference, long amountCents,
                                               String idempotencyKey, UUID internalRefundId,
                                               CallNotPermittedException ex) {
        throw gatewayUnavailable(ex);
    }

    private GatewayRefundResult refundFallback(String pspReference, long amountCents,
                                               String idempotencyKey, UUID internalRefundId,
                                               Exception ex) {
        throw rethrow(ex);
    }

    private GatewayStatusResult getStatusFallback(String pspReference,
                                                  CallNotPermittedException ex) {
        throw gatewayUnavailable(ex);
    }

    private GatewayStatusResult getStatusFallback(String pspReference, Exception ex) {
        throw rethrow(ex);
    }

    private GatewayRefundStatusResult getRefundStatusFallback(String pspRefundId,
                                                              CallNotPermittedException ex) {
        throw gatewayUnavailable(ex);
    }

    private GatewayRefundStatusResult getRefundStatusFallback(String pspRefundId, Exception ex) {
        throw rethrow(ex);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private PaymentGatewayUnavailableException gatewayUnavailable(CallNotPermittedException ex) {
        log.error("Payment gateway circuit breaker is open — rejecting call: {}", ex.getMessage());
        return new PaymentGatewayUnavailableException(
            "Payment gateway is temporarily unavailable (circuit breaker open)", ex);
    }

    private RuntimeException rethrow(Exception ex) {
        if (ex instanceof RuntimeException rte) return rte;
        return new PaymentGatewayException("Gateway call failed", ex);
    }
}
