package com.instacommerce.audit.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class InternalServiceAuthFilterTest {

    private static final String SHARED_TOKEN = "shared-audit-internal-token-123";
    private static final String ADMIN_GATEWAY_TOKEN = "admin-gateway-per-service-token-456";
    private static final String ORDER_SERVICE_TOKEN = "order-service-per-service-token-789";

    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        filterChain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Accepts valid per-service token and grants only ROLE_INTERNAL_SERVICE")
    void acceptsValidPerServiceToken() throws Exception {
        InternalServiceAuthFilter filter = new InternalServiceAuthFilter(
                SHARED_TOKEN,
                Map.of("admin-gateway-service", ADMIN_GATEWAY_TOKEN),
                false);

        when(request.getHeader("X-Internal-Service")).thenReturn("admin-gateway-service");
        when(request.getHeader("X-Internal-Token")).thenReturn(ADMIN_GATEWAY_TOKEN);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo("admin-gateway-service");
        assertThat(auth.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_INTERNAL_SERVICE")
                .doesNotContain("ROLE_ADMIN");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Rejects shared token when caller has a distinct per-service token configured")
    void rejectsSharedTokenWhenPerServiceTokenConfigured() throws Exception {
        InternalServiceAuthFilter filter = new InternalServiceAuthFilter(
                SHARED_TOKEN,
                Map.of("admin-gateway-service", ADMIN_GATEWAY_TOKEN),
                false);

        when(request.getHeader("X-Internal-Service")).thenReturn("admin-gateway-service");
        when(request.getHeader("X-Internal-Token")).thenReturn(SHARED_TOKEN);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Accepts shared token fallback when strict per-service mode is disabled and caller not mapped")
    void acceptsSharedTokenFallbackWhenNotStrict() throws Exception {
        InternalServiceAuthFilter filter = new InternalServiceAuthFilter(
                SHARED_TOKEN,
                Map.of("admin-gateway-service", ADMIN_GATEWAY_TOKEN),
                false);

        when(request.getHeader("X-Internal-Service")).thenReturn("order-service");
        when(request.getHeader("X-Internal-Token")).thenReturn(SHARED_TOKEN);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo("order-service");
        assertThat(auth.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_INTERNAL_SERVICE");
    }

    @Test
    @DisplayName("Rejects shared token fallback when strict per-service mode is enabled")
    void rejectsSharedTokenWhenStrictModeEnabled() throws Exception {
        InternalServiceAuthFilter filter = new InternalServiceAuthFilter(
                SHARED_TOKEN,
                Map.of("order-service", ORDER_SERVICE_TOKEN),
                true);

        when(request.getHeader("X-Internal-Service")).thenReturn("payment-service");
        when(request.getHeader("X-Internal-Token")).thenReturn(SHARED_TOKEN);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Rejects empty token even when shared token is unconfigured")
    void rejectsBlankOrEmptyTokenWhenUnconfigured() throws Exception {
        InternalServiceAuthFilter filter = new InternalServiceAuthFilter("", Map.of(), false);

        when(request.getHeader("X-Internal-Service")).thenReturn("order-service");
        when(request.getHeader("X-Internal-Token")).thenReturn("");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Rejects missing service name or invalid token")
    void rejectsMissingServiceOrInvalidToken() throws Exception {
        InternalServiceAuthFilter filter = new InternalServiceAuthFilter(SHARED_TOKEN, Map.of(), false);

        when(request.getHeader("X-Internal-Service")).thenReturn(" ");
        when(request.getHeader("X-Internal-Token")).thenReturn(SHARED_TOKEN);
        filter.doFilterInternal(request, response, filterChain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        when(request.getHeader("X-Internal-Service")).thenReturn("order-service");
        when(request.getHeader("X-Internal-Token")).thenReturn("wrong-token");
        filter.doFilterInternal(request, response, filterChain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
