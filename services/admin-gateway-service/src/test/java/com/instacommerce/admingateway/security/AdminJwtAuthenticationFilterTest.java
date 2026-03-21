package com.instacommerce.admingateway.security;

import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class AdminJwtAuthenticationFilterTest {
    @Mock
    private JwtService jwtService;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain filterChain;
    @Mock
    private Jws<Claims> jws;
    @Mock
    private Claims claims;

    private AdminJwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        filter = new AdminJwtAuthenticationFilter(jwtService, objectMapper);
    }

    @Test
    void shouldAllowAccessWithValidJwt() throws Exception {
        when(request.getRequestURI()).thenReturn("/admin/v1/dashboard");
        when(request.getHeader("Authorization")).thenReturn("Bearer validToken");
        when(jwtService.validateAccessToken("validToken")).thenReturn(jws);
        when(jws.getPayload()).thenReturn(claims);
        when(claims.getSubject()).thenReturn("admin-user-1");
        when(jwtService.extractAuthorities(claims)).thenReturn(java.util.List.of(
            new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")
        ));

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void shouldRejectExpiredJwt() throws Exception {
        when(request.getRequestURI()).thenReturn("/admin/v1/dashboard");
        when(request.getHeader("Authorization")).thenReturn("Bearer expiredToken");
        when(jwtService.validateAccessToken("expiredToken"))
            .thenThrow(new JwtException("Token expired"));

        java.io.StringWriter writer = new java.io.StringWriter();
        when(response.getWriter()).thenReturn(new java.io.PrintWriter(writer));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(response).setContentType(MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    void shouldRejectWrongAudience() throws Exception {
        when(request.getRequestURI()).thenReturn("/admin/v1/dashboard");
        when(request.getHeader("Authorization")).thenReturn("Bearer wrongAudToken");
        when(jwtService.validateAccessToken("wrongAudToken"))
            .thenThrow(new JwtException("Token audience does not match"));

        java.io.StringWriter writer = new java.io.StringWriter();
        when(response.getWriter()).thenReturn(new java.io.PrintWriter(writer));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void shouldRejectInvalidSignature() throws Exception {
        when(request.getRequestURI()).thenReturn("/admin/v1/dashboard");
        when(request.getHeader("Authorization")).thenReturn("Bearer invalidSignature");
        when(jwtService.validateAccessToken("invalidSignature"))
            .thenThrow(new JwtException("Invalid signature"));

        java.io.StringWriter writer = new java.io.StringWriter();
        when(response.getWriter()).thenReturn(new java.io.PrintWriter(writer));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void shouldAllowUnauthenticatedAccessToHealth() throws Exception {
        when(request.getRequestURI()).thenReturn("/admin/health");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(jwtService, never()).validateAccessToken(anyString());
    }

    @Test
    void shouldAllowUnauthenticatedAccessToMetrics() throws Exception {
        when(request.getRequestURI()).thenReturn("/admin/metrics");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(jwtService, never()).validateAccessToken(anyString());
    }

    @Test
    void shouldAllowMissingBearerToken() throws Exception {
        when(request.getRequestURI()).thenReturn("/admin/v1/dashboard");
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(jwtService, never()).validateAccessToken(anyString());
    }
}
