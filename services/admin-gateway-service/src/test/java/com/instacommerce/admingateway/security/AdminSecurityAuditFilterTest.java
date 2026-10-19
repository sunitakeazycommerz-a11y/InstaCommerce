package com.instacommerce.admingateway.security;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.instacommerce.admingateway.audit.AdminAuditClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AdminSecurityAuditFilterTest {

    @Mock
    private AdminAuditClient auditClient;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain filterChain;

    private AdminSecurityAuditFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        filter = new AdminSecurityAuditFilter(auditClient);
    }

    @Test
    @DisplayName("should publish audit event on administrative POST mutation")
    void shouldPublishAuditEventOnPost() throws Exception {
        UUID adminId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(adminId.toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")))
        );

        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/admin/v1/flags/promo-flag/override");
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.195");
        when(request.getHeader("X-Correlation-ID")).thenReturn("corr-123");
        when(request.getHeader("User-Agent")).thenReturn("AdminClient/1.0");
        when(response.getStatus()).thenReturn(200);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(auditClient).publishAuditEvent(
            eq("ADMIN_MUTATION"),
            eq(adminId),
            eq("ADMIN"),
            eq("flags"),
            eq("promo-flag"),
            eq("POST"),
            anyMap(),
            eq("203.0.113.195"),
            eq("AdminClient/1.0"),
            eq("corr-123")
        );
    }

    @Test
    @DisplayName("should NOT publish audit event on GET requests")
    void shouldNotPublishOnGet() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/admin/v1/dashboard");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(auditClient);
    }

    @Test
    @DisplayName("should fallback to remoteAddr when X-Forwarded-For contains spoofed invalid characters")
    void shouldDetectSpoofedIpAndFallback() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("super-admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")))
        );

        when(request.getMethod()).thenReturn("DELETE");
        when(request.getRequestURI()).thenReturn("/admin/v1/users/user-456");
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");
        when(request.getHeader("X-Forwarded-For")).thenReturn("invalid-ip-injection<script>");
        when(response.getStatus()).thenReturn(204);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(auditClient).publishAuditEvent(
            eq("ADMIN_MUTATION"),
            isNull(),
            eq("ADMIN"),
            eq("users"),
            eq("user-456"),
            eq("DELETE"),
            anyMap(),
            eq("192.168.1.100"),
            any(),
            any()
        );
    }

    @Test
    @DisplayName("audit client failures should not interrupt HTTP request handling")
    void auditClientFailureDoesNotDisruptRequest() throws Exception {
        when(request.getMethod()).thenReturn("PUT");
        when(request.getRequestURI()).thenReturn("/admin/v1/pricing/surge");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(response.getStatus()).thenReturn(200);

        doThrow(new RuntimeException("Connection refused to audit service"))
            .when(auditClient).publishAuditEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }
}
