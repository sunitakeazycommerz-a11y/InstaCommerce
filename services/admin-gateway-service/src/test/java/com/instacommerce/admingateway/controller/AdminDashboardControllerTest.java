package com.instacommerce.admingateway.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnHealthyDashboardWhenAllDownstreamServicesAreUp() throws Exception {
        when(restTemplate.exchange(
                eq("http://order-service:8085/actuator/health"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
            .thenReturn(ResponseEntity.ok(Map.of("status", "UP")));

        when(restTemplate.exchange(
                eq("http://order-service:8085/actuator/metrics/http.server.requests"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"name\":\"http.server.requests\",\"measurements\":[{\"statistic\":\"COUNT\",\"value\":42.0}]}"));

        when(restTemplate.exchange(
                eq("http://payment-service:8086/actuator/health"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
            .thenReturn(ResponseEntity.ok(Map.of("status", "UP")));

        when(restTemplate.exchange(
                eq("http://payment-service:8086/actuator/metrics/http.server.requests"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
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
    @SuppressWarnings("unchecked")
    void shouldMarkUnreachableServicesDegradedWithoutFailingDashboard() throws Exception {
        when(restTemplate.exchange(
                eq("http://order-service:8085/actuator/health"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
            .thenReturn(ResponseEntity.ok(Map.of("status", "UP")));

        when(restTemplate.exchange(
                eq("http://order-service:8085/actuator/metrics/http.server.requests"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"measurements\":[{\"statistic\":\"COUNT\",\"value\":15.0}]}"));

        when(restTemplate.exchange(
                eq("http://payment-service:8086/actuator/health"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
            .thenThrow(new ResourceAccessException("Connection refused"));

        when(restTemplate.exchange(
                eq("http://payment-service:8086/actuator/metrics/http.server.requests"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
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
    @SuppressWarnings("unchecked")
    void shouldBoundSlowDownstreamServiceCallsAndMarkTimedOutServiceDegraded() throws Exception {
        CountDownLatch slowCallLatch = new CountDownLatch(1);
        when(restTemplate.exchange(
                eq("http://order-service:8085/actuator/health"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
            .thenReturn(ResponseEntity.ok(Map.of("status", "UP")));

        when(restTemplate.exchange(
                eq("http://order-service:8085/actuator/metrics/http.server.requests"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"measurements\":[{\"statistic\":\"COUNT\",\"value\":5.0}]}"));

        when(restTemplate.exchange(
                eq("http://payment-service:8086/actuator/health"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
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
    @SuppressWarnings("unchecked")
    void shouldReturnFeatureFlags() throws Exception {
        Map<String, Object> flags = Map.of("feature-a", true, "feature-b", false);
        when(restTemplate.exchange(
                eq("http://config-feature-flag-service:8098/flags"),
                eq(HttpMethod.GET),
                isNull(),
                any(ParameterizedTypeReference.class)))
            .thenReturn(ResponseEntity.ok(flags));

        mockMvc.perform(get("/admin/v1/flags")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.feature-a").value(true))
            .andExpect(jsonPath("$.feature-b").value(false));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @SuppressWarnings("unchecked")
    void shouldHandleFeatureFlagsServiceError() throws Exception {
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                isNull(),
                any(ParameterizedTypeReference.class)))
            .thenThrow(new RuntimeException("Service unavailable"));

        mockMvc.perform(get("/admin/v1/flags")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error").exists());
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
    @SuppressWarnings("unchecked")
    void shouldOverrideFeatureFlagViaFeatureFlagServiceWithInternalHeaders() throws Exception {
        when(restTemplate.exchange(
                eq("http://config-feature-flag-service:8098/admin/flags/{key}/overrides"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class),
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

        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
            eq("http://config-feature-flag-service:8098/admin/flags/{key}/overrides"),
            eq(HttpMethod.POST),
            captor.capture(),
            any(ParameterizedTypeReference.class),
            eq("feature-a"));

        HttpEntity<Map<String, Object>> sentEntity = captor.getValue();
        assertThat(sentEntity.getHeaders().getFirst("X-Internal-Service")).isEqualTo("admin-gateway-service");
        assertThat(sentEntity.getHeaders().getFirst("X-Internal-Token")).isEqualTo("test-internal-service-token");
        assertThat(sentEntity.getBody()).containsEntry("userId", userId);
        assertThat(sentEntity.getBody()).containsEntry("value", "true");
        assertThat(sentEntity.getBody()).containsKey("expiresAt");
    }

    @Test
    @WithMockUser(username = "superadmin", roles = {"ADMIN", "SUPER_ADMIN"})
    @SuppressWarnings("unchecked")
    void shouldPropagateDownstreamHttpErrorOnFeatureFlagOverride() throws Exception {
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        when(restTemplate.exchange(
                eq("http://config-feature-flag-service:8098/admin/flags/{key}/overrides"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class),
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
            .andExpect(jsonPath("$.message").value("Flag not found: missing-flag"));
    }

    @Test
    @WithMockUser(username = "superadmin", roles = {"ADMIN", "SUPER_ADMIN"})
    @SuppressWarnings("unchecked")
    void shouldReturnBadGatewayWhenFeatureFlagServiceUnreachable() throws Exception {
        when(restTemplate.exchange(
                eq("http://config-feature-flag-service:8098/admin/flags/{key}/overrides"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class),
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
    @SuppressWarnings("unchecked")
    void shouldReturnPendingReconciliation() throws Exception {
        Map<String, Object> pending = Map.of(
            "items", new Object[]{
                Map.of("id", "rec-1", "status", "PENDING")
            });
        when(restTemplate.exchange(
                eq("http://reconciliation-engine:8107/pending"),
                eq(HttpMethod.GET),
                isNull(),
                any(ParameterizedTypeReference.class)))
            .thenReturn(ResponseEntity.ok(pending));

        mockMvc.perform(get("/admin/v1/reconciliation/pending")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value("rec-1"));
    }

    @Test
    void shouldRejectPendingReconciliationWithoutAdminRole() throws Exception {
        mockMvc.perform(get("/admin/v1/reconciliation/pending")
                .with(csrf()))
            .andExpect(status().isUnauthorized());
    }
}
