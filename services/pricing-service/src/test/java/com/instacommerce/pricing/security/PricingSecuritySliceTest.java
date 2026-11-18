package com.instacommerce.pricing.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.pricing.config.CacheConfig;
import com.instacommerce.pricing.config.JacksonConfig;
import com.instacommerce.pricing.config.SecurityConfig;
import com.instacommerce.pricing.controller.PricingController;
import com.instacommerce.pricing.exception.GlobalExceptionHandler;
import com.instacommerce.pricing.exception.TraceIdProvider;
import com.instacommerce.pricing.service.CouponService;
import com.instacommerce.pricing.service.PricingService;
import com.instacommerce.pricing.service.QuoteTokenService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(classes = PricingSecuritySliceTest.SliceConfig.class)
@TestPropertySource(properties = {
    "internal.service.token=test-internal-secret",
    "pricing.cache.redis.enabled=true"
})
@DisplayName("Pricing Security WebMvc Slice Tests")
class PricingSecuritySliceTest {

    @Configuration
    @EnableWebMvc
    @Import({
        SecurityConfig.class,
        CacheConfig.class,
        JacksonConfig.class,
        InternalServiceAuthFilter.class,
        JwtAuthenticationFilter.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        TraceIdProvider.class,
        PricingController.class
    })
    static class SliceConfig {
        @Bean
        static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        PricingService pricingService() {
            return mock(PricingService.class);
        }

        @Bean
        CouponService couponService() {
            return mock(CouponService.class);
        }

        @Bean
        QuoteTokenService quoteTokenService() {
            return mock(QuoteTokenService.class);
        }

        @Bean
        JwtService jwtService() {
            return mock(JwtService.class);
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CouponService couponService;

    @Autowired
    private QuoteTokenService quoteTokenService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .build();
    }

    @Test
    @DisplayName("Customer role cannot invoke internal coupon redemption or quote validation")
    void customerCannotInvokeInternalEndpoints() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String redeemBody = """
            {"code":"SAVE10","userId":"%s","orderId":"%s","discountCents":500}
            """.formatted(userId, orderId);

        mockMvc.perform(post("/pricing/coupons/redeem")
                .with(user("cust-1").roles("CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(redeemBody))
            .andExpect(status().isForbidden());

        String quoteBody = """
            {"quoteId":"%s","quoteToken":"sig","totalCents":1000,"subtotalCents":1000,"discountCents":0}
            """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/v1/pricing/quotes/validate")
                .with(user("cust-1").roles("CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(quoteBody))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Internal service token authorizes coupon redemption and quote validation on /pricing and /api/v1/pricing")
    void internalServiceAuthorizesRedeemAndValidate() throws Exception {
        when(quoteTokenService.validate(any(), any(), anyLong(), anyLong(), anyLong()))
            .thenReturn(QuoteTokenService.ValidationResult.valid());

        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String redeemBody = """
            {"code":"SAVE10","userId":"%s","orderId":"%s","discountCents":500}
            """.formatted(userId, orderId);

        mockMvc.perform(post("/api/v1/pricing/coupons/redeem")
                .header("X-Internal-Service", "order-service")
                .header("X-Internal-Token", "test-internal-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content(redeemBody))
            .andExpect(status().isOk());

        verify(couponService).redeemCoupon("SAVE10", userId, orderId, 500L);

        String quoteBody = """
            {"quoteId":"%s","quoteToken":"sig","totalCents":1000,"subtotalCents":1000,"discountCents":0}
            """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/pricing/quotes/validate")
                .header("X-Internal-Service", "checkout-orchestrator-service")
                .header("X-Internal-Token", "test-internal-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content(quoteBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    @DisplayName("Blank or mismatched internal token returns 401 on protected endpoints")
    void invalidInternalTokenRejected() throws Exception {
        String quoteBody = """
            {"quoteId":"%s","quoteToken":"sig","totalCents":1000,"subtotalCents":1000,"discountCents":0}
            """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/pricing/quotes/validate")
                .header("X-Internal-Service", "order-service")
                .header("X-Internal-Token", "")
                .contentType(MediaType.APPLICATION_JSON)
                .content(quoteBody))
            .andExpect(status().isUnauthorized());
    }
}
