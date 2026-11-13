package com.instacommerce.catalog.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.catalog.config.CacheConfig;
import com.instacommerce.catalog.config.JacksonConfig;
import com.instacommerce.catalog.config.SecurityConfig;
import com.instacommerce.catalog.controller.PricingController;
import com.instacommerce.catalog.dto.response.PricingBreakdownResponse;
import com.instacommerce.catalog.exception.GlobalExceptionHandler;
import com.instacommerce.catalog.exception.TraceIdProvider;
import com.instacommerce.catalog.service.PricingService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PricingController.class)
@Import({
    SecurityConfig.class,
    CacheConfig.class,
    JacksonConfig.class,
    InternalServiceAuthFilter.class,
    JwtAuthenticationFilter.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    GlobalExceptionHandler.class,
    TraceIdProvider.class
})
@TestPropertySource(properties = {
    "internal.service.token=test-internal-secret",
    "catalog.cache.redis.enabled=true"
})
@DisplayName("Catalog Security WebMvc Slice Tests")
class CatalogSecuritySliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PricingService pricingService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    @DisplayName("Internal service headers authenticate protected /pricing/compute endpoint cleanly even without Redis")
    void internalServiceTokenAuthenticatesProtectedEndpoint() throws Exception {
        when(pricingService.compute(any())).thenReturn(
            new PricingBreakdownResponse(List.of(), 1000L, null, 1000L, "INR"));

        String body = """
            {
              "storeId": "store-1",
              "items": [{"productId": "%s", "quantity": 1}]
            }
            """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/pricing/compute")
                .header("X-Internal-Service", "checkout-orchestrator-service")
                .header("X-Internal-Token", "test-internal-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCents").value(1000));
    }

    @Test
    @DisplayName("Protected /pricing/compute rejects missing or invalid internal credentials with 401")
    void invalidOrMissingCredentialsReturn401() throws Exception {
        String body = """
            {
              "storeId": "store-1",
              "items": [{"productId": "%s", "quantity": 1}]
            }
            """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/pricing/compute")
                .header("X-Internal-Service", "checkout-orchestrator-service")
                .header("X-Internal-Token", "wrong-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized());
    }
}
