package com.instacommerce.order.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("AdaptiveConcurrencyFilter Unit Tests")
class AdaptiveConcurrencyFilterTest {

    private VegasConcurrencyLimiter limiter;
    private AdaptiveConcurrencyFilter filter;

    @BeforeEach
    void setUp() {
        limiter = new VegasConcurrencyLimiter();
        filter = new AdaptiveConcurrencyFilter(limiter);
    }

    @Test
    @DisplayName("Should allow requests within concurrency capacity")
    void shouldAllowRequestsWithinLimit() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Should bypass health endpoints without concurrency check")
    void shouldBypassActuatorHealth() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health/readiness");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("Should return 429 and Retry-After when limit exceeded")
    void shouldDropExcessTrafficWith429() throws ServletException, IOException {
        limiter.setCurrentLimitForTest(1.0);

        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/api/v1/orders/1");
        MockHttpServletResponse res1 = new MockHttpServletResponse();

        // Acquire the single available slot
        assertThat(limiter.tryAcquire()).isTrue();

        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/api/v1/orders/2");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        FilterChain chain2 = mock(FilterChain.class);

        filter.doFilter(req2, res2, chain2);

        assertThat(res2.getStatus()).isEqualTo(429);
        assertThat(res2.getHeader("Retry-After")).isEqualTo("1");
        assertThat(res2.getContentAsString()).contains("Too Many Requests");

        // Clean up acquired slot
        limiter.releaseWithoutSample();
    }

    @Test
    @DisplayName("Should contract concurrency limit when observed latency inflates (TCP Vegas gradient)")
    void shouldContractLimitUnderHighLatency() {
        limiter.setCurrentLimitForTest(50.0);
        double initialLimit = limiter.getCurrentLimit();

        // Simulate 20 high latency samples (e.g. 200ms vs 10ms baseline)
        for (int i = 0; i < 20; i++) {
            limiter.tryAcquire();
            limiter.onSample(250);
        }

        assertThat(limiter.getCurrentLimit()).isLessThan(initialLimit);
    }
}
