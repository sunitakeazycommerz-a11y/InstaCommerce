package com.instacommerce.admingateway.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "audit-trail.enabled=false",
    "admin-gateway.dashboard.timeout-ms=1000",
    "admin-gateway.dashboard.per-service-timeout-ms=500",
    "admin-gateway.dashboard.services[0].name=order-service",
    "admin-gateway.dashboard.services[0].base-url=http://order-service:8085",
    "admin-gateway.dashboard.services[0].health-path=/actuator/health",
    "admin-gateway.dashboard.services[0].metrics-path=/actuator/metrics/http.server.requests",
    "admin-gateway.dashboard.services[1].name=payment-service",
    "admin-gateway.dashboard.services[1].base-url=http://payment-service:8086",
    "admin-gateway.dashboard.services[1].health-path=/actuator/health",
    "admin-gateway.dashboard.services[1].metrics-path=/actuator/metrics/http.server.requests"
})
class AdminDashboardControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RestTemplate restTemplate;

    private static HttpEntity<?> anyEntity() {
        return ArgumentMatchers.any();
    }

    private static ParameterizedTypeReference<Map<String, Object>> anyMapType() {
        return ArgumentMatchers.any();
    }

    @Test
    void shouldReturnHealthyDashboardWhenAllDownstreamServicesAreUp() throws Exception {
        when(restTemplate.exchange(
                eq("http://order-service:8085/actuator/health"),
                eq(HttpMethod.GET),
                anyEntity(),
                anyMapType()))
            .thenReturn(ResponseEntity.ok(Map.of("status", "UP")));

        when(restTemplate.exchange(
                eq("http://order-service:8085/actuator/metrics/http.server.requests"),
                eq(HttpMethod.GET),
                anyEntity(),
                eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"name\":\"http.server.requests\",\"measurements\":[{\"statistic\":\"COUNT\",\"value\":42.0}]}"));

        when(restTemplate.exchange(
                eq("http://payment-service:8086/actuator/health"),
                eq(HttpMethod.GET),
                anyEntity(),
                anyMapType()))
            .thenReturn(ResponseEntity.ok(Map.of("status", "UP")));

        when(restTemplate.exchange(
                eq("http://payment-service:8086/actuator/metrics/http.server.requests"),
                eq(HttpMethod.GET),
                anyEntity(),
                eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"name\":\"http.server.requests\",\"measurements\":[{\"statistic\":\"COUNT\",\"value\":18.0}]}"));

        mockMvc.perform(get("/admin/v1/dashboard")
                .with(csrf())
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.orderVolume").doesNotExist())
            .andExpect(jsonPath("$.paymentVolume").doesNotExist())
            .andExpect(jsonPath("$.fulfillmentRate").doesNotExist())
            .andExpect(jsonPath("$.summary.totalServices").value(2))
            .andExpect(jsonPath("$.summary.healthyServices").value(2))
            .andExpect(jsonPath("$.summary.degradedServices").value(0))
            .andExpect(jsonPath("$.summary.totalRequests").value(60.0))
            .andExpect(jsonPath("$.services.order-service.status").value("UP"))
            .andExpect(jsonPath("$.services.order-service.metrics.COUNT").value(42.0))
            .andExpect(jsonPath("$.services.payment-service.status").value("UP"))
            .andExpect(jsonPath("$.services.payment-service.metrics.COUNT").value(18.0));
    }

    @Test
    void shouldMarkUnreachableServicesDegradedWithoutFailingDashboard() throws Exception {
        when(restTemplate.exchange(
                eq("http://order-service:8085/actuator/health"),
                eq(HttpMethod.GET),
                anyEntity(),
                anyMapType()))
            .thenReturn(ResponseEntity.ok(Map.of("status", "UP")));

        when(restTemplate.exchange(
                eq("http://order-service:8085/actuator/metrics/http.server.requests"),
                eq(HttpMethod.GET),
                anyEntity(),
                eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"measurements\":[{\"statistic\":\"COUNT\",\"value\":15.0}]}"));

        when(restTemplate.exchange(
                eq("http://payment-service:8086/actuator/health"),
                eq(HttpMethod.GET),
                anyEntity(),
                anyMapType()))
            .thenThrow(new ResourceAccessException("Connection refused"));

        when(restTemplate.exchange(
                eq("http://payment-service:8086/actuator/metrics/http.server.requests"),
                eq(HttpMethod.GET),
                anyEntity(),
                eq(String.class)))
            .thenThrow(new ResourceAccessException("Connection refused"));

        mockMvc.perform(get("/admin/v1/dashboard")
                .with(csrf())
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("degraded"))
            .andExpect(jsonPath("$.summary.totalServices").value(2))
            .andExpect(jsonPath("$.summary.healthyServices").value(1))
            .andExpect(jsonPath("$.summary.degradedServices").value(1))
            .andExpect(jsonPath("$.services.order-service.status").value("UP"))
            .andExpect(jsonPath("$.services.payment-service.status").value("DEGRADED"))
            .andExpect(jsonPath("$.services.payment-service.error").exists());
    }

    @Test
    void shouldBoundSlowDownstreamServiceCallsAndMarkTimedOutServiceDegraded() throws Exception {
        CountDownLatch slowCallLatch = new CountDownLatch(1);
        when(restTemplate.exchange(
                eq("http://order-service:8085/actuator/health"),
                eq(HttpMethod.GET),
                anyEntity(),
                anyMapType()))
            .thenReturn(ResponseEntity.ok(Map.of("status", "UP")));

        when(restTemplate.exchange(
                eq("http://order-service:8085/actuator/metrics/http.server.requests"),
                eq(HttpMethod.GET),
                anyEntity(),
                eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"measurements\":[{\"statistic\":\"COUNT\",\"value\":5.0}]}"));

        when(restTemplate.exchange(
                eq("http://payment-service:8086/actuator/health"),
                eq(HttpMethod.GET),
                anyEntity(),
                anyMapType()))
            .thenAnswer(invocation -> {
                slowCallLatch.await(3, TimeUnit.SECONDS);
                return ResponseEntity.ok(Map.of("status", "UP"));
            });

        try {
            mockMvc.perform(get("/admin/v1/dashboard")
                    .with(csrf())
                    .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("degraded"))
                .andExpect(jsonPath("$.summary.timedOut").value(true))
                .andExpect(jsonPath("$.services.order-service.status").value("UP"))
                .andExpect(jsonPath("$.services.payment-service.status").value("DEGRADED"));
        } finally {
            slowCallLatch.countDown();
        }
    }

    @Test
    void shouldRejectDashboardWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/admin/v1/dashboard"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void shouldReturnFeatureFlags() throws Exception {
        Map<String, Object> flags = Map.of("feature-a", true, "feature-b", false);
        when(restTemplate.exchange(
                eq("http://config-feature-flag-service:8098/admin/flags"),
                eq(HttpMethod.GET),
                isNull(),
                anyMapType()))
            .thenReturn(ResponseEntity.ok(flags));

        mockMvc.perform(get("/admin/v1/flags")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.feature-a").value(true))
            .andExpect(jsonPath("$.feature-b").value(false));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void shouldHandleFeatureFlagsServiceError() throws Exception {
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                isNull(),
                anyMapType()))
            .thenThrow(new RuntimeException("Service unavailable"));

        mockMvc.perform(get("/admin/v1/flags")
                .with(csrf()))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void shouldReturnBadGatewayProblemDetailWhenFlagsOrReconciliationUnreachable() throws Exception {
        when(restTemplate.exchange(
                eq("http://config-feature-flag-service:8098/admin/flags"),
                eq(HttpMethod.GET),
                isNull(),
                anyMapType()))
            .thenThrow(new ResourceAccessException("Connection refused"));

        mockMvc.perform(get("/admin/v1/flags")
                .with(csrf()))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"))
            .andExpect(jsonPath("$.status").value(502))
            .andExpect(jsonPath("$.detail").value("Connection refused"));

        when(restTemplate.exchange(
                eq("http://reconciliation-engine:8107/pending"),
                eq(HttpMethod.GET),
                isNull(),
                anyMapType()))
            .thenThrow(new ResourceAccessException("Timeout"));

        mockMvc.perform(get("/admin/v1/reconciliation/pending")
                .with(csrf()))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"))
            .andExpect(jsonPath("$.status").value(502))
            .andExpect(jsonPath("$.detail").value("Timeout"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void shouldRejectFeatureFlagOverrideWithoutSuperAdminRole() throws Exception {
        mockMvc.perform(post("/admin/v1/flags/feature-a/override")
                .with(csrf())
                .contentType("application/json")
                .content("{\"value\": true, \"ttlSeconds\": 600}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @WithMockUser(username = "superadmin", roles = {"ADMIN", "SUPER_ADMIN"})
    void shouldOverrideFeatureFlagViaFeatureFlagServiceWithJsonPayload() throws Exception {
        when(restTemplate.exchange(
                eq("http://config-feature-flag-service:8098/admin/flags/{key}/overrides"),
                eq(HttpMethod.POST),
                anyEntity(),
                anyMapType(),
                eq("feature-a")))
            .thenReturn(ResponseEntity.status(HttpStatus.CREATED).build());

        String userId = "11111111-1111-1111-1111-111111111111";
        mockMvc.perform(post("/admin/v1/flags/feature-a/override")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\": \"" + userId + "\", \"value\": \"true\", \"reason\": \"canary\", \"ttlSeconds\": 600}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.flagId").value("feature-a"))
            .andExpect(jsonPath("$.status").value("applied"))
            .andExpect(jsonPath("$.httpStatus").value(201));

        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.captor();
        verify(restTemplate).exchange(
            eq("http://config-feature-flag-service:8098/admin/flags/{key}/overrides"),
            eq(HttpMethod.POST),
            captor.capture(),
            anyMapType(),
            eq("feature-a"));

        HttpEntity<Map<String, Object>> sentEntity = captor.getValue();
        assertThat(sentEntity.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(sentEntity.getBody()).containsEntry("userId", userId);
        assertThat(sentEntity.getBody()).containsEntry("value", "true");
        assertThat(sentEntity.getBody()).containsKey("expiresAt");
    }

    @Test
    @WithMockUser(username = "superadmin", roles = {"ADMIN", "SUPER_ADMIN"})
    void shouldPropagateDownstreamHttpErrorOnFeatureFlagOverride() throws Exception {
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        when(restTemplate.exchange(
                eq("http://config-feature-flag-service:8098/admin/flags/{key}/overrides"),
                eq(HttpMethod.POST),
                anyEntity(),
                anyMapType(),
                eq("missing-flag")))
            .thenThrow(HttpClientErrorException.create(
                HttpStatus.NOT_FOUND,
                "Not Found",
                responseHeaders,
                "{\"code\":\"FLAG_NOT_FOUND\",\"message\":\"Flag not found: missing-flag\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

        mockMvc.perform(post("/admin/v1/flags/missing-flag/override")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\": \"11111111-1111-1111-1111-111111111111\", \"value\": \"true\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("FLAG_NOT_FOUND"))
            .andExpect(jsonPath("$.detail").value("Flag not found: missing-flag"))
            .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @WithMockUser(username = "superadmin", roles = {"ADMIN", "SUPER_ADMIN"})
    void shouldReturnBadGatewayWhenFeatureFlagServiceUnreachable() throws Exception {
        when(restTemplate.exchange(
                eq("http://config-feature-flag-service:8098/admin/flags/{key}/overrides"),
                eq(HttpMethod.POST),
                anyEntity(),
                anyMapType(),
                eq("feature-a")))
            .thenThrow(new ResourceAccessException("Connection refused"));

        mockMvc.perform(post("/admin/v1/flags/feature-a/override")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\": \"11111111-1111-1111-1111-111111111111\", \"value\": \"true\"}"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void shouldReturnPendingReconciliation() throws Exception {
        Map<String, Object> pending = Map.of(
            "items", new Object[]{
                Map.of("id", "rec-1", "status", "PENDING")
            });
        when(restTemplate.exchange(
                eq("http://reconciliation-engine:8107/pending"),
                eq(HttpMethod.GET),
                isNull(),
                anyMapType()))
            .thenReturn(ResponseEntity.ok(pending));

        mockMvc.perform(get("/admin/v1/reconciliation/pending")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value("rec-1"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void shouldReturnReconciliationSummary() throws Exception {
        Map<String, Object> summary = Map.of(
            "count", 2,
            "runs", new Object[]{
                Map.of("id", "run-1", "status", "COMPLETED")
            });
        when(restTemplate.exchange(
                eq("http://reconciliation-engine:8107/reconciliation/runs"),
                eq(HttpMethod.GET),
                eq(HttpEntity.EMPTY),
                anyMapType()))
            .thenReturn(ResponseEntity.ok(summary));

        mockMvc.perform(get("/admin/v1/reconciliation/summary")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void shouldReturnBadGatewayWhenReconciliationSummaryUnreachable() throws Exception {
        when(restTemplate.exchange(
                eq("http://reconciliation-engine:8107/reconciliation/runs"),
                eq(HttpMethod.GET),
                anyEntity(),
                anyMapType()))
            .thenThrow(new ResourceAccessException("Connection refused"));

        mockMvc.perform(get("/admin/v1/reconciliation/summary")
                .with(csrf()))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
    }

    @Test
    void shouldRejectPendingReconciliationWithoutAdminRole() throws Exception {
        mockMvc.perform(get("/admin/v1/reconciliation/pending")
                .with(csrf()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void shouldEnableAndDisableFlagForAdminWhileReservingEmergencyStopForSuperAdmin() throws Exception {
        when(restTemplate.exchange(
                eq("http://config-feature-flag-service:8098/admin/flags/{key}/enable"),
                eq(HttpMethod.POST),
                eq(HttpEntity.EMPTY),
                anyMapType(),
                eq("checkout_v2")))
            .thenReturn(ResponseEntity.ok(Map.of("key", "checkout_v2", "enabled", true)));

        when(restTemplate.exchange(
                eq("http://config-feature-flag-service:8098/admin/flags/{key}/disable"),
                eq(HttpMethod.POST),
                eq(HttpEntity.EMPTY),
                anyMapType(),
                eq("checkout_v2")))
            .thenReturn(ResponseEntity.ok(Map.of("key", "checkout_v2", "enabled", false)));

        mockMvc.perform(post("/admin/v1/flags/checkout_v2/enable").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(post("/admin/v1/flags/checkout_v2/disable").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(post("/admin/v1/flags/checkout_v2/emergency-stop").with(csrf()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @WithMockUser(username = "superadmin", roles = {"ADMIN", "SUPER_ADMIN"})
    void shouldTriggerEmergencyStopForSuperAdmin() throws Exception {
        when(restTemplate.exchange(
                eq("http://config-feature-flag-service:8098/admin/flags/emergency-stop/{key}"),
                eq(HttpMethod.POST),
                eq(HttpEntity.EMPTY),
                anyMapType(),
                eq("checkout_v2")))
            .thenReturn(ResponseEntity.ok().build());

        mockMvc.perform(post("/admin/v1/flags/checkout_v2/emergency-stop").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.flagKey").value("checkout_v2"))
            .andExpect(jsonPath("$.status").value("emergency_stopped"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void shouldProxyFraudOpsEndpointsForAdmin() throws Exception {
        when(restTemplate.exchange(
                ArgumentMatchers.argThat((java.net.URI u) -> u != null && u.toString().contains("/admin/fraud/rules")),
                eq(HttpMethod.GET),
                isNull(),
                anyMapType()))
            .thenReturn(ResponseEntity.ok(Map.of("content", java.util.List.of(Map.of("name", "velocity-1")))));

        when(restTemplate.exchange(
                eq("http://fraud-detection-service:8097/admin/fraud/rules"),
                eq(HttpMethod.POST),
                anyEntity(),
                anyMapType()))
            .thenReturn(ResponseEntity.status(HttpStatus.CREATED).body(Map.of("name", "new-rule")));

        when(restTemplate.exchange(
                ArgumentMatchers.argThat((java.net.URI u) -> u != null && u.toString().contains("/admin/fraud/blocklist")),
                eq(HttpMethod.GET),
                isNull(),
                anyMapType()))
            .thenReturn(ResponseEntity.ok(Map.of("content", java.util.List.of())));

        when(restTemplate.exchange(
                eq("http://fraud-detection-service:8097/admin/fraud/blocklist"),
                eq(HttpMethod.POST),
                anyEntity(),
                anyMapType()))
            .thenReturn(ResponseEntity.status(HttpStatus.CREATED).body(Map.of("entityValue", "1.2.3.4")));

        when(restTemplate.exchange(
                eq("http://fraud-detection-service:8097/admin/fraud/allowlist"),
                eq(HttpMethod.GET),
                isNull(),
                anyMapType()))
            .thenReturn(ResponseEntity.ok(Map.of("items", java.util.List.of(Map.of("entityValue", "vip-1")))));

        when(restTemplate.exchange(
                eq("http://fraud-detection-service:8097/admin/fraud/allowlist"),
                eq(HttpMethod.POST),
                anyEntity(),
                anyMapType()))
            .thenReturn(ResponseEntity.status(HttpStatus.CREATED).body(Map.of("entityValue", "vip-1")));

        when(restTemplate.exchange(
                eq("http://fraud-detection-service:8097/admin/fraud/allowlist/{id}"),
                eq(HttpMethod.DELETE),
                eq(HttpEntity.EMPTY),
                anyMapType(),
                eq("11111111-1111-1111-1111-111111111111")))
            .thenReturn(ResponseEntity.noContent().build());

        mockMvc.perform(get("/admin/v1/fraud/rules").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].name").value("velocity-1"));

        mockMvc.perform(post("/admin/v1/fraud/rules")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"new-rule\",\"ruleType\":\"AMOUNT\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("new-rule"));

        mockMvc.perform(get("/admin/v1/fraud/blocklist").with(csrf()))
            .andExpect(status().isOk());

        mockMvc.perform(post("/admin/v1/fraud/blocklist")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entityType\":\"IP\",\"entityValue\":\"1.2.3.4\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entityValue").value("1.2.3.4"));

        mockMvc.perform(get("/admin/v1/fraud/allowlist").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].entityValue").value("vip-1"));

        mockMvc.perform(post("/admin/v1/fraud/allowlist")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entityType\":\"USER\",\"entityValue\":\"vip-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entityValue").value("vip-1"));

        mockMvc.perform(delete("/admin/v1/fraud/allowlist/11111111-1111-1111-1111-111111111111").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("revoked"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void shouldProxyAuditEventsAndIntegrityVerify() throws Exception {
        when(restTemplate.exchange(
                ArgumentMatchers.argThat((java.net.URI u) -> u != null && u.toString().contains("/admin/audit/events")),
                eq(HttpMethod.GET),
                anyEntity(),
                anyMapType()))
            .thenReturn(ResponseEntity.ok(Map.of("content", java.util.List.of(Map.of("eventType", "ORDER_CREATED")))));

        when(restTemplate.exchange(
                ArgumentMatchers.argThat((java.net.URI u) -> u != null && u.toString().contains("/admin/audit/integrity/verify")),
                eq(HttpMethod.GET),
                anyEntity(),
                anyMapType()))
            .thenReturn(ResponseEntity.ok(Map.of("eventsVerified", 12, "chainBreaks", 0, "intact", true)));

        mockMvc.perform(get("/admin/v1/audit/events?eventType=ORDER_CREATED").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].eventType").value("ORDER_CREATED"));

        mockMvc.perform(get("/admin/v1/audit/integrity/verify?batchSize=500").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intact").value(true))
            .andExpect(jsonPath("$.eventsVerified").value(12));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void shouldValidateAndEncodeAuditQueryParametersSafely() throws Exception {
        when(restTemplate.exchange(
                ArgumentMatchers.any(URI.class),
                eq(HttpMethod.GET),
                anyEntity(),
                anyMapType()))
            .thenReturn(ResponseEntity.ok(Map.of("content", java.util.List.of())));

        String actorId = "11111111-1111-1111-1111-111111111111";
        mockMvc.perform(get("/admin/v1/audit/events")
                .param("actorId", actorId)
                .param("resourceId", "ord-1&evil=true{foo}#bar")
                .param("fromDate", "2026-11-25T00:00:00Z")
                .param("toDate", "2026-11-25T12:00:00Z")
                .with(csrf()))
            .andExpect(status().isOk());

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uriCaptor.capture(), eq(HttpMethod.GET), anyEntity(), anyMapType());
        URI sentUri = uriCaptor.getValue();
        assertThat(sentUri.getScheme()).isEqualTo("http");
        assertThat(sentUri.getHost()).isEqualTo("audit-trail-service");
        assertThat(sentUri.getPort()).isEqualTo(8096);
        assertThat(sentUri.getPath()).isEqualTo("/admin/audit/events");
        assertThat(sentUri.getRawQuery())
            .contains("actorId=" + actorId)
            .contains("resourceId=ord-1%26evil%3Dtrue%7Bfoo%7D%23bar")
            .contains("fromDate=2026-11-25T00%3A00%3A00Z")
            .contains("toDate=2026-11-25T12%3A00%3A00Z");

        mockMvc.perform(get("/admin/v1/audit/events")
                .param("actorId", "not-a-uuid")
                .with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/admin/v1/audit/integrity/verify")
                .param("from", "not-an-instant")
                .with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/admin/v1/audit/events")
                .param("resourceId", "bad\nvalue")
                .with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
