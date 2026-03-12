package com.instacommerce.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.payment.exception.PaymentDeclinedException;
import com.instacommerce.payment.exception.PaymentGatewayException;
import com.instacommerce.payment.exception.PaymentGatewayTransientException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Validates Resilience4j circuit-breaker, retry, and time-limiter behavior
 * for PSP gateway calls using the same configuration parameters as application.yml.
 *
 * <p>Tests use the programmatic Resilience4j API (not Spring AOP annotations)
 * to verify resilience semantics in isolation. The annotation wiring is validated
 * by Resilience4j's own integration tests and by the Spring context in integration tests.</p>
 */
@ExtendWith(MockitoExtension.class)
class PaymentGatewayResilienceTest {

    @Mock
    private PaymentGateway delegate;

    private CircuitBreaker circuitBreaker;
    private Retry retry;

    @BeforeEach
    void setUp() {
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
            .slidingWindowType(SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(20)
            .minimumNumberOfCalls(10)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(5)
            .automaticTransitionFromOpenToHalfOpenEnabled(false)
            .recordExceptions(IOException.class, TimeoutException.class,
                PaymentGatewayException.class)
            .ignoreExceptions(PaymentDeclinedException.class)
            .build();
        circuitBreaker = CircuitBreaker.of("paymentGateway", cbConfig);

        RetryConfig retryConfig = RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(10))
            .retryExceptions(IOException.class, TimeoutException.class,
                PaymentGatewayTransientException.class)
            .ignoreExceptions(PaymentDeclinedException.class)
            .build();
        retry = Retry.of("paymentGateway", retryConfig);
    }

    private GatewayAuthRequest sampleRequest() {
        return new GatewayAuthRequest(10_000L, "INR", "idem-key-1", "pm_card_visa");
    }

    // -----------------------------------------------------------------------
    // Circuit Breaker
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Circuit breaker opens after failure threshold is reached")
    void circuitBreaker_opensAfterFailureThreshold() {
        when(delegate.authorize(any()))
            .thenThrow(new PaymentGatewayException("PSP error"));

        Supplier<GatewayAuthResult> decorated = CircuitBreaker.decorateSupplier(
            circuitBreaker, () -> delegate.authorize(sampleRequest()));

        // minimumNumberOfCalls = 10, all failures → 100% > 50% threshold
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(decorated::get)
                .isInstanceOf(PaymentGatewayException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("Circuit breaker rejects calls when open")
    void circuitBreaker_rejectsCallsWhenOpen() {
        when(delegate.authorize(any()))
            .thenThrow(new PaymentGatewayException("PSP error"));

        Supplier<GatewayAuthResult> decorated = CircuitBreaker.decorateSupplier(
            circuitBreaker, () -> delegate.authorize(sampleRequest()));

        for (int i = 0; i < 10; i++) {
            try { decorated.get(); } catch (Exception ignored) { }
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Next call must be rejected without invoking the delegate
        assertThatThrownBy(decorated::get)
            .isInstanceOf(CallNotPermittedException.class);

        verify(delegate, times(10)).authorize(any());
    }

    @Test
    @DisplayName("Circuit breaker allows calls in half-open state")
    void circuitBreaker_allowsCallsWhenHalfOpen() {
        AtomicInteger callCount = new AtomicInteger(0);

        Supplier<GatewayAuthResult> decorated = CircuitBreaker.decorateSupplier(
            circuitBreaker, () -> {
                int n = callCount.incrementAndGet();
                if (n <= 10) {
                    throw new PaymentGatewayException("PSP error #" + n);
                }
                return GatewayAuthResult.success("pi_recovered");
            });

        // Trip the circuit breaker
        for (int i = 0; i < 10; i++) {
            try { decorated.get(); } catch (Exception ignored) { }
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Manually transition to half-open for deterministic testing
        circuitBreaker.transitionToHalfOpenState();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // A call in half-open state should be permitted and succeed
        GatewayAuthResult result = decorated.get();
        assertThat(result.success()).isTrue();
        assertThat(result.pspReference()).isEqualTo("pi_recovered");
    }

    // -----------------------------------------------------------------------
    // Retry
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Retry retries on transient IOException failure")
    void retry_retriesOnTransientFailure() throws Throwable {
        AtomicInteger attempts = new AtomicInteger(0);

        io.github.resilience4j.core.functions.CheckedSupplier<GatewayAuthResult> supplier = () -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                throw new IOException("Connection reset");
            }
            return GatewayAuthResult.success("pi_" + attempt);
        };

        io.github.resilience4j.core.functions.CheckedSupplier<GatewayAuthResult> decorated =
            Retry.decorateCheckedSupplier(retry, supplier);

        GatewayAuthResult result = decorated.get();
        assertThat(result.success()).isTrue();
        assertThat(attempts.get()).isEqualTo(3); // 2 failures + 1 success
    }

    @Test
    @DisplayName("Retry retries on PaymentGatewayTransientException")
    void retry_retriesOnTransientPspException() throws Throwable {
        AtomicInteger attempts = new AtomicInteger(0);

        io.github.resilience4j.core.functions.CheckedSupplier<GatewayAuthResult> supplier = () -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                throw new PaymentGatewayTransientException("Connection reset",
                    new IOException("Connection reset"));
            }
            return GatewayAuthResult.success("pi_" + attempt);
        };

        io.github.resilience4j.core.functions.CheckedSupplier<GatewayAuthResult> decorated =
            Retry.decorateCheckedSupplier(retry, supplier);

        GatewayAuthResult result = decorated.get();
        assertThat(result.success()).isTrue();
        assertThat(attempts.get()).isEqualTo(3); // 2 transient failures + 1 success
    }

    @Test
    @DisplayName("Retry does not retry on PaymentGatewayException (PSP business error)")
    void retry_doesNotRetryOnBusinessException() {
        AtomicInteger attempts = new AtomicInteger(0);

        Supplier<GatewayAuthResult> supplier = () -> {
            attempts.incrementAndGet();
            throw new PaymentGatewayException("Card declined by processor");
        };

        Supplier<GatewayAuthResult> decorated = Retry.decorateSupplier(retry, supplier);

        assertThatThrownBy(decorated::get)
            .isInstanceOf(PaymentGatewayException.class);

        assertThat(attempts.get()).isEqualTo(1); // no retry
    }

    @Test
    @DisplayName("Retry does not retry on PaymentDeclinedException")
    void retry_doesNotRetryOnDeclinedException() {
        AtomicInteger attempts = new AtomicInteger(0);

        Supplier<GatewayAuthResult> supplier = () -> {
            attempts.incrementAndGet();
            throw new PaymentDeclinedException("Insufficient funds");
        };

        Supplier<GatewayAuthResult> decorated = Retry.decorateSupplier(retry, supplier);

        assertThatThrownBy(decorated::get)
            .isInstanceOf(PaymentDeclinedException.class);

        assertThat(attempts.get()).isEqualTo(1); // no retry
    }

    // -----------------------------------------------------------------------
    // TimeLimiter
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("TimeLimiter times out slow calls")
    void timelimiter_timesOutSlowCalls() {
        TimeLimiterConfig config = TimeLimiterConfig.custom()
            .timeoutDuration(Duration.ofMillis(200))
            .cancelRunningFuture(true)
            .build();
        TimeLimiter timeLimiter = TimeLimiter.of("paymentGateway", config);

        Supplier<CompletableFuture<GatewayAuthResult>> futureSupplier = () ->
            CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return GatewayAuthResult.success("pi_slow");
            });

        Callable<GatewayAuthResult> decorated =
            TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier);

        assertThatThrownBy(decorated::call)
            .isInstanceOf(TimeoutException.class);
    }
}
