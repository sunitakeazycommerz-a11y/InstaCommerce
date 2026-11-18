package com.instacommerce.admingateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
        doReturn(List.of(
            new SimpleGrantedAuthority("ROLE_ADMIN")
        )).when(jwtService).extractAuthorities(claims);

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

        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

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

        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void shouldRejectInvalidSignature() throws Exception {
        when(request.getRequestURI()).thenReturn("/admin/v1/dashboard");
        when(request.getHeader("Authorization")).thenReturn("Bearer invalidSignature");
        when(jwtService.validateAccessToken("invalidSignature"))
            .thenThrow(new JwtException("Invalid signature"));

        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

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

    @Test
    void shouldStripSpoofedAdminAndInternalHeadersBeforePassingChain() throws Exception {
        MockHttpServletRequest mockRequest = new MockHttpServletRequest("GET", "/admin/v1/dashboard");
        mockRequest.addHeader("X-Admin-Id", "spoofed-admin");
        mockRequest.addHeader("X-Internal-Service", "spoofed-svc");
        mockRequest.addHeader("X-Internal-Token", "spoofed-token");
        mockRequest.addHeader("X-Trace-Id", "trace-123");

        filter.doFilterInternal(mockRequest, response, filterChain);

        ArgumentCaptor<HttpServletRequest> captor = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(filterChain).doFilter(captor.capture(), eq(response));
        HttpServletRequest passed = captor.getValue();
        assertThat(passed.getHeader("X-Admin-Id")).isNull();
        assertThat(passed.getHeader("X-Internal-Service")).isNull();
        assertThat(passed.getHeader("X-Internal-Token")).isNull();
        assertThat(passed.getHeader("X-Trace-Id")).isEqualTo("trace-123");
    }

    @Test
    void shouldRejectApiAudienceTokenWithoutAdminRole() throws Exception {
        when(request.getRequestURI()).thenReturn("/admin/v1/dashboard");
        when(request.getHeader("Authorization")).thenReturn("Bearer customerToken");
        when(jwtService.validateAccessToken("customerToken")).thenReturn(jws);
        when(jws.getPayload()).thenReturn(claims);
        when(claims.getSubject()).thenReturn("cust-1");
        when(claims.getAudience()).thenReturn(Set.of("instacommerce-api"));
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
            .when(jwtService).extractAuthorities(claims);

        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(filterChain, never()).doFilter(any(), any());
    }
}
