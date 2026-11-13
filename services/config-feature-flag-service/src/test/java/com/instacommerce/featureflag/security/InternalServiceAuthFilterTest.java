package com.instacommerce.featureflag.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class InternalServiceAuthFilterTest {

    private static final String SHARED_TOKEN = "shared-secret-token";
    private static final String GATEWAY_TOKEN = "admin-gateway-per-service-token";

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterChain = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("authenticates valid shared internal token with ROLE_INTERNAL_SERVICE only")
    void acceptsValidSharedToken() throws Exception {
        InternalServiceAuthFilter filter = new InternalServiceAuthFilter(SHARED_TOKEN, Map.of());
        request.addHeader("X-Internal-Service", "admin-gateway-service");
        request.addHeader("X-Internal-Token", SHARED_TOKEN);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo("admin-gateway-service");
        assertThat(auth.getAuthorities())
            .extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_INTERNAL_SERVICE");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("prefers per-service token over shared token when configured")
    void acceptsPerServiceTokenAndRejectsSharedForConfiguredCaller() throws Exception {
        InternalServiceAuthFilter filter = new InternalServiceAuthFilter(
            SHARED_TOKEN,
            Map.of("admin-gateway-service", GATEWAY_TOKEN));

        request.addHeader("X-Internal-Service", "admin-gateway-service");
        request.addHeader("X-Internal-Token", SHARED_TOKEN);
        filter.doFilterInternal(request, response, filterChain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        MockHttpServletRequest validRequest = new MockHttpServletRequest();
        validRequest.addHeader("X-Internal-Service", "admin-gateway-service");
        validRequest.addHeader("X-Internal-Token", GATEWAY_TOKEN);
        filter.doFilterInternal(validRequest, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
            .isEqualTo("admin-gateway-service");
    }

    @Test
    @DisplayName("fails closed when configured shared token and caller token are blank")
    void rejectsBlankTokenAndUnconfiguredSharedToken() throws Exception {
        InternalServiceAuthFilter filter = new InternalServiceAuthFilter("", Map.of());
        request.addHeader("X-Internal-Service", "admin-gateway-service");
        request.addHeader("X-Internal-Token", "");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("does not authenticate when X-Internal-Service header is blank")
    void rejectsBlankServiceHeader() throws Exception {
        InternalServiceAuthFilter filter = new InternalServiceAuthFilter(SHARED_TOKEN, Map.of());
        request.addHeader("X-Internal-Service", "   ");
        request.addHeader("X-Internal-Token", SHARED_TOKEN);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
