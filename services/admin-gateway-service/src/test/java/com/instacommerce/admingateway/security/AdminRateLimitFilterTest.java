package com.instacommerce.admingateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class AdminRateLimitFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldEnforceRequestsPerMinuteLimit() throws Exception {
        AdminRateLimitFilter filter = new AdminRateLimitFilter(new ObjectMapper(), 2);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/admin/v1/dashboard");
        req1.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilter(req1, res1, chain);

        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/admin/v1/dashboard");
        req2.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilter(req2, res2, chain);

        MockHttpServletRequest req3 = new MockHttpServletRequest("GET", "/admin/v1/dashboard");
        req3.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse res3 = new MockHttpServletResponse();
        filter.doFilter(req3, res3, chain);

        verify(chain).doFilter(req1, res1);
        verify(chain).doFilter(req2, res2);
        verifyNoMoreInteractions(chain);
        assertThat(res3.getStatus()).isEqualTo(429);
        assertThat(res3.getHeader("Retry-After")).isEqualTo("60");
        assertThat(res3.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
    }
}
