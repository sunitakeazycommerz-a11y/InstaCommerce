package com.instacommerce.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.identity.exception.TraceIdProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("JwtAuthenticationFilter internal authority merge tests")
class JwtAuthenticationFilterTest {

    private JwtService jwtService;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        filter = new JwtAuthenticationFilter(jwtService, mock(TraceIdProvider.class), new ObjectMapper());
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Preserves ROLE_INTERNAL_SERVICE and ROLE_SERVICE_* when Bearer JWT is also present")
    void mergesInternalServiceAuthoritiesWithJwtAuthorities() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "mobile-bff-service",
                null,
                List.of(
                        new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE"),
                        new SimpleGrantedAuthority("ROLE_SERVICE_MOBILE_BFF_SERVICE"))));

        Jws<Claims> jws = mock();
        Claims claims = mock(Claims.class);
        when(jwtService.validateAccessToken("customer-jwt")).thenReturn(jws);
        when(jws.getPayload()).thenReturn(claims);
        when(claims.getSubject()).thenReturn("usr_123");
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .when(jwtService).extractAuthorities(claims);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/users/usr_123");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer customer-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo("usr_123");
        assertThat(auth.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder(
                        "ROLE_USER",
                        "ROLE_INTERNAL_SERVICE",
                        "ROLE_SERVICE_MOBILE_BFF_SERVICE");
        verify(chain).doFilter(request, response);
    }
}
