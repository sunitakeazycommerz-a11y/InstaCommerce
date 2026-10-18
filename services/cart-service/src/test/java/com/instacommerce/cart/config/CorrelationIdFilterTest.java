package com.instacommerce.cart.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void shouldPopulateMdcFromHeadersAndCleanUpAfterRequest() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-ID", "corr-test-999");
        request.addHeader("X-Store-Id", "store-bangalore-01");
        request.addHeader("X-User-Id", "user-uuid-123");
        request.addHeader("X-Tenant-Id", "tenant-in-blr");

        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> capturedCorrelationId = new AtomicReference<>();
        AtomicReference<String> capturedStoreId = new AtomicReference<>();
        AtomicReference<String> capturedUserId = new AtomicReference<>();
        AtomicReference<String> capturedTenantId = new AtomicReference<>();

        FilterChain chain = (req, res) -> {
            capturedCorrelationId.set(MDC.get("correlationId"));
            capturedStoreId.set(MDC.get("storeId"));
            capturedUserId.set(MDC.get("userId"));
            capturedTenantId.set(MDC.get("tenantId"));
        };

        filter.doFilter(request, response, chain);

        assertThat(capturedCorrelationId.get()).isEqualTo("corr-test-999");
        assertThat(capturedStoreId.get()).isEqualTo("store-bangalore-01");
        assertThat(capturedUserId.get()).isEqualTo("user-uuid-123");
        assertThat(capturedTenantId.get()).isEqualTo("tenant-in-blr");
        assertThat(response.getHeader("X-Correlation-ID")).isEqualTo("corr-test-999");

        // Verify MDC cleaned up
        assertThat(MDC.get("correlationId")).isNull();
        assertThat(MDC.get("storeId")).isNull();
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("tenantId")).isNull();
    }
}
