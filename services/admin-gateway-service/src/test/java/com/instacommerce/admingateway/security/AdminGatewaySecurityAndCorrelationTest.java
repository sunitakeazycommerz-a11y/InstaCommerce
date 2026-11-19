package com.instacommerce.admingateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.instacommerce.admingateway.audit.AdminAuditClient;
import com.instacommerce.admingateway.config.UpstreamRestClientConfig;
import com.instacommerce.admingateway.controller.AdminDashboardController;
import com.instacommerce.admingateway.service.DashboardAggregationService;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;

class AdminGatewaySecurityAndCorrelationTest {

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("AdminSecurityAuditFilter ignores spoofed X-Forwarded-For from public remoteAddr and walks right-to-left on trusted proxy chains")
    void trustedProxyRightToLeftXffParsing() throws Exception {
        AdminAuditClient auditClient = mock(AdminAuditClient.class);
        AdminSecurityAuditFilter filter = new AdminSecurityAuditFilter(auditClient);
        UUID adminId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(adminId.toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));

        MockHttpServletRequest untrustedReq = new MockHttpServletRequest("POST", "/admin/v1/flags/f1/override");
        untrustedReq.setRemoteAddr("198.51.100.77");
        untrustedReq.addHeader("X-Forwarded-For", "127.0.0.1");
        MockHttpServletResponse untrustedRes = new MockHttpServletResponse();
        untrustedRes.setStatus(200);
        filter.doFilterInternal(untrustedReq, untrustedRes, (r, s) -> {});

        verify(auditClient).publishAuditEvent(
                eq("ADMIN_MUTATION"), eq(adminId), eq("ADMIN"), eq("flags"), eq("f1"),
                eq("POST"), anyMap(), eq("198.51.100.77"), eq(null), eq(null));

        MockHttpServletRequest proxiedReq = new MockHttpServletRequest("DELETE", "/admin/v1/flags/f2/override");
        proxiedReq.setRemoteAddr("10.0.0.5");
        proxiedReq.addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.42, 10.0.0.9");
        MockHttpServletResponse proxiedRes = new MockHttpServletResponse();
        proxiedRes.setStatus(200);
        filter.doFilterInternal(proxiedReq, proxiedRes, (r, s) -> {});

        verify(auditClient).publishAuditEvent(
                eq("ADMIN_MUTATION"), eq(adminId), eq("ADMIN"), eq("flags"), eq("f2"),
                eq("DELETE"), anyMap(), eq("203.0.113.42"), eq(null), eq(null));
    }

    @Test
    @DisplayName("CorrelationIdFilter generates correlation ID and propagates X-Correlation-ID via RestTemplate interceptor")
    void correlationIdFilterPropagatesToRestTemplate() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        RestTemplate restTemplate = new UpstreamRestClientConfig()
                .restTemplate(1000, 1000, "admin-gateway-service", "internal-tok");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(MockRestRequestMatchers.requestTo("http://feature-flag-service:8098/flags"))
                .andExpect(MockRestRequestMatchers.header("X-Correlation-ID", "corr-admin-900"))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"ok\":true}"));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/v1/flags");
        req.addHeader("X-Correlation-ID", "corr-admin-900");
        MockHttpServletResponse res = new MockHttpServletResponse();

        AtomicReference<String> bodyRef = new AtomicReference<>();
        FilterChain chain = (r, s) -> bodyRef.set(restTemplate.getForObject("http://feature-flag-service:8098/flags", String.class));
        filter.doFilter(req, res, chain);

        assertThat(res.getHeader("X-Correlation-ID")).isEqualTo("corr-admin-900");
        assertThat(bodyRef.get()).contains("ok");
        server.verify();
    }

    @Test
    @DisplayName("AdminDashboardController rejects malformed flag IDs and out-of-range override TTL")
    void adminDashboardControllerRejectsInvalidFlagIdAndTtl() {
        AdminDashboardController controller = new AdminDashboardController(
                mock(RestTemplate.class),
                mock(DashboardAggregationService.class),
                "http://feature-flag-service:8098",
                "http://reconciliation-engine:8107");

        assertThatThrownBy(() -> controller.overrideFlag("../etc/passwd", Map.of("enabled", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid flag id");

        assertThatThrownBy(() -> controller.overrideFlag("checkout.express", Map.of("ttlSeconds", 99_999_999L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttlSeconds must be between");
    }
}
