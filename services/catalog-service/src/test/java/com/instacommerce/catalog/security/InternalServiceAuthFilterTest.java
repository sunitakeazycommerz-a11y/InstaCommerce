package com.instacommerce.catalog.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("Catalog InternalServiceAuthFilter Unit Tests")
class InternalServiceAuthFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Valid shared token grants ROLE_INTERNAL_SERVICE without ROLE_ADMIN")
    void validSharedTokenAuthenticatesInternalService() throws Exception {
        InternalServiceAuthFilter filter = new InternalServiceAuthFilter("shared-secret-value", Map.of());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/pricing/compute");
        request.addHeader("X-Internal-Service", "checkout-orchestrator-service");
        request.addHeader("X-Internal-Token", "shared-secret-value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("checkout-orchestrator-service");
        assertThat(auth.getAuthorities())
            .extracting("authority")
            .containsExactly("ROLE_INTERNAL_SERVICE");
    }

    @Test
    @DisplayName("Per-service allowed-callers token overrides shared token")
    void perServiceTokenOverridesSharedToken() throws Exception {
        InternalServiceAuthFilter filter = new InternalServiceAuthFilter(
            "shared-secret",
            Map.of("order-service", "order-specific-secret"));

        MockHttpServletRequest usingShared = new MockHttpServletRequest("POST", "/pricing/compute");
        usingShared.addHeader("X-Internal-Service", "order-service");
        usingShared.addHeader("X-Internal-Token", "shared-secret");
        filter.doFilter(usingShared, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        MockHttpServletRequest usingPerService = new MockHttpServletRequest("POST", "/pricing/compute");
        usingPerService.addHeader("X-Internal-Service", "order-service");
        usingPerService.addHeader("X-Internal-Token", "order-specific-secret");
        filter.doFilter(usingPerService, new MockHttpServletResponse(), mock(FilterChain.class));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("order-service");
    }

    @Test
    @DisplayName("Blank configured token or blank request headers fail closed")
    void blankTokensAndMissingHeadersFailClosed() throws Exception {
        InternalServiceAuthFilter blankTokenFilter = new InternalServiceAuthFilter("", Map.of("order-service", ""));

        MockHttpServletRequest emptyTokenReq = new MockHttpServletRequest("POST", "/pricing/compute");
        emptyTokenReq.addHeader("X-Internal-Service", "order-service");
        emptyTokenReq.addHeader("X-Internal-Token", "");
        blankTokenFilter.doFilter(emptyTokenReq, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        MockHttpServletRequest whitespaceServiceReq = new MockHttpServletRequest("POST", "/pricing/compute");
        whitespaceServiceReq.addHeader("X-Internal-Service", "   ");
        whitespaceServiceReq.addHeader("X-Internal-Token", "any");
        InternalServiceAuthFilter configuredFilter = new InternalServiceAuthFilter("secret", Map.of());
        configuredFilter.doFilter(whitespaceServiceReq, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        MockHttpServletRequest wrongTokenReq = new MockHttpServletRequest("POST", "/pricing/compute");
        wrongTokenReq.addHeader("X-Internal-Service", "search-service");
        wrongTokenReq.addHeader("X-Internal-Token", "wrong");
        configuredFilter.doFilter(wrongTokenReq, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
