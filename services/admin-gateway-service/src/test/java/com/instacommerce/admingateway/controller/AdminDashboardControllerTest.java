package com.instacommerce.admingateway.controller;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class AdminDashboardControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RestTemplate restTemplate;

    @Test
    void shouldReturnDashboardWithAdminRole() throws Exception {
        mockMvc.perform(get("/admin/v1/dashboard")
                .with(csrf())
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.orderVolume").exists())
            .andExpect(jsonPath("$.paymentVolume").exists())
            .andExpect(jsonPath("$.fulfillmentRate").exists());
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
        when(restTemplate.getForObject("http://config-feature-flag-service:8095/flags", Map.class))
            .thenReturn(flags);

        mockMvc.perform(get("/admin/v1/flags")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.feature-a").value(true))
            .andExpect(jsonPath("$.feature-b").value(false));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void shouldHandleFeatureFlagsServiceError() throws Exception {
        when(restTemplate.getForObject(anyString(), eq(Map.class)))
            .thenThrow(new RuntimeException("Service unavailable"));

        mockMvc.perform(get("/admin/v1/flags")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void shouldOverrideFeatureFlag() throws Exception {
        mockMvc.perform(post("/admin/v1/flags/feature-a/override")
                .with(csrf())
                .contentType("application/json")
                .content("{\"value\": true, \"ttlSeconds\": 600}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.flagId").value("feature-a"))
            .andExpect(jsonPath("$.override").value(true))
            .andExpect(jsonPath("$.expiresIn").value(600));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void shouldReturnPendingReconciliation() throws Exception {
        Map<String, Object> pending = Map.of(
            "items", new Object[]{
                Map.of("id", "rec-1", "status", "PENDING")
            });
        when(restTemplate.getForObject("http://reconciliation-engine:8098/pending", Map.class))
            .thenReturn(pending);

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
