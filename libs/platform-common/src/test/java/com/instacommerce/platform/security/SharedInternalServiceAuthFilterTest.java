package com.instacommerce.platform.security;

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

@DisplayName("SharedInternalServiceAuthFilter tests")
class SharedInternalServiceAuthFilterTest {

    private static final String SHARED_TOKEN = "shared-secret-token";
    private static final String CALLER_TOKEN = "order-service-secret";

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
    @DisplayName("Trims caller name and grants ROLE_INTERNAL_SERVICE and ROLE_SERVICE_<CALLER>")
    void trimsServiceAndGrantsScopedRoles() throws Exception {
        InternalServiceProperties props = new InternalServiceProperties();
        props.setToken(SHARED_TOKEN);
        props.setAllowedCallers(Map.of("order-service", CALLER_TOKEN));
        SharedInternalServiceAuthFilter filter = new SharedInternalServiceAuthFilter(props);

        when(request.getHeader(SharedInternalServiceAuthFilter.HEADER_INTERNAL_SERVICE))
                .thenReturn("  order-service  ");
        when(request.getHeader(SharedInternalServiceAuthFilter.HEADER_INTERNAL_TOKEN))
                .thenReturn(CALLER_TOKEN);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo("order-service");
        assertThat(auth.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_INTERNAL_SERVICE", "ROLE_SERVICE_ORDER_SERVICE");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Fails closed when configured and per-service secrets are blank")
    void failsClosedOnBlankTokens() throws Exception {
        SharedInternalServiceAuthFilter filter = new SharedInternalServiceAuthFilter(
                "", Map.of("order-service", ""));

        when(request.getHeader(SharedInternalServiceAuthFilter.HEADER_INTERNAL_SERVICE))
                .thenReturn("order-service");
        when(request.getHeader(SharedInternalServiceAuthFilter.HEADER_INTERNAL_TOKEN))
                .thenReturn("");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Strict per-service mode rejects shared token fallback")
    void strictModeRejectsSharedToken() throws Exception {
        SharedInternalServiceAuthFilter filter = new SharedInternalServiceAuthFilter(
                SHARED_TOKEN, Map.of("payment-service", CALLER_TOKEN), true);

        when(request.getHeader(SharedInternalServiceAuthFilter.HEADER_INTERNAL_SERVICE))
                .thenReturn("order-service");
        when(request.getHeader(SharedInternalServiceAuthFilter.HEADER_INTERNAL_TOKEN))
                .thenReturn(SHARED_TOKEN);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
