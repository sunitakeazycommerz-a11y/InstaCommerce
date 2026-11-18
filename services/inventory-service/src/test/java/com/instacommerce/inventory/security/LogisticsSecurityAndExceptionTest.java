package com.instacommerce.inventory.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.inventory.config.JacksonConfig;
import com.instacommerce.inventory.config.SecurityConfig;
import com.instacommerce.inventory.controller.InboundReceivingController;
import com.instacommerce.inventory.controller.PerishableController;
import com.instacommerce.inventory.controller.SafetyStockController;
import com.instacommerce.inventory.controller.StockController;
import com.instacommerce.inventory.controller.StockReconciliationController;
import com.instacommerce.inventory.exception.GlobalExceptionHandler;
import com.instacommerce.inventory.exception.TraceIdProvider;
import com.instacommerce.inventory.service.InboundReceivingService;
import com.instacommerce.inventory.service.InventoryService;
import com.instacommerce.inventory.service.PerishableLiquidationService;
import com.instacommerce.inventory.service.RateLimitService;
import com.instacommerce.inventory.service.SafetyStockService;
import com.instacommerce.inventory.service.StockReconciliationService;
import com.instacommerce.platform.security.InternalServiceSecurityConfiguration;
import com.instacommerce.platform.security.SharedInternalServiceAuthFilter;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebMvcTest(controllers = {
    InboundReceivingController.class,
    PerishableController.class,
    SafetyStockController.class,
    StockReconciliationController.class,
    StockController.class
})
@Import({
    SecurityConfig.class,
    InternalServiceSecurityConfiguration.class,
    JwtAuthenticationFilter.class,
    ClientIpResolver.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    TraceIdProvider.class,
    JacksonConfig.class,
    GlobalExceptionHandler.class
})
@TestPropertySource(properties = {
    "internal.service.token=test-internal-token"
})
class LogisticsSecurityAndExceptionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private InboundReceivingService receivingService;

    @MockitoBean
    private PerishableLiquidationService perishableService;

    @MockitoBean
    private SafetyStockService safetyStockService;

    @MockitoBean
    private StockReconciliationService reconciliationService;

    @MockitoBean
    private InventoryService inventoryService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @Test
    @DisplayName("SharedInternalServiceAuthFilter grants ROLE_INTERNAL_SERVICE and caller-scoped role and fails closed on blank")
    void internalFilterGrantsCallerRoleAndFailsClosed() throws Exception {
        SharedInternalServiceAuthFilter filter = new SharedInternalServiceAuthFilter("", Map.of());
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Internal-Service", "fulfillment-service");
        req.addHeader("X-Internal-Token", "");
        SecurityContextHolder.clearContext();
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        SharedInternalServiceAuthFilter activeFilter = new SharedInternalServiceAuthFilter("secret", Map.of());
        req = new MockHttpServletRequest();
        req.addHeader("X-Internal-Service", "fulfillment-service");
        req.addHeader("X-Internal-Token", "secret");
        activeFilter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
            .extracting("authority")
            .containsExactlyInAnyOrder("ROLE_INTERNAL_SERVICE", "ROLE_SERVICE_FULFILLMENT_SERVICE");
        SecurityContextHolder.clearContext();
    }

    @Test
    @WithMockUser(username = "user-1", roles = {"USER"})
    @DisplayName("Write controllers reject plain USER role with 403")
    void writeControllersRejectPlainUser() throws Exception {
        UUID storeId = UUID.randomUUID();
        mockMvc.perform(post("/receiving/pallets/PALLET-1/receive"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/stores/{storeId}/perishables/trigger-markdowns", storeId))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/stores/{storeId}/safety-stock/check-reorders", storeId))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/inventory/reconciliation/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeId\":\"" + storeId + "\",\"physicalCounts\":[]}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GlobalExceptionHandler maps NoSuchElement, IllegalState, IllegalArgument to 404/409/400")
    void globalExceptionHandlerMapsDomainExceptions() throws Exception {
        when(receivingService.receiveByPalletBarcode(any(), any(), any()))
            .thenThrow(new NoSuchElementException("ASN not found"))
            .thenThrow(new IllegalStateException("Already received"))
            .thenThrow(new IllegalArgumentException("Negative quantity"));

        mockMvc.perform(post("/receiving/pallets/MISSING/receive")
                .header("X-Internal-Service", "warehouse-service")
                .header("X-Internal-Token", "test-internal-token"))
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/receiving/pallets/DUP/receive")
                .header("X-Internal-Service", "warehouse-service")
                .header("X-Internal-Token", "test-internal-token"))
            .andExpect(status().isConflict());

        mockMvc.perform(post("/receiving/pallets/BAD/receive")
                .header("X-Internal-Service", "warehouse-service")
                .header("X-Internal-Token", "test-internal-token"))
            .andExpect(status().isBadRequest());
    }
}
