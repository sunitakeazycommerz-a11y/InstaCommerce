package com.instacommerce.pricing.controller;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.pricing.dto.request.RollbackCouponUsageRequest;
import com.instacommerce.pricing.dto.request.UnredeemCouponRequest;
import com.instacommerce.pricing.service.CouponService;
import com.instacommerce.pricing.service.PricingService;
import com.instacommerce.pricing.service.QuoteTokenService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PricingControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private PricingService pricingService;
    @Mock private CouponService couponService;
    @Mock private QuoteTokenService quoteTokenService;

    @BeforeEach
    void setUp() {
        PricingController controller = new PricingController(pricingService, couponService, quoteTokenService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST /pricing/coupons/unredeem invokes couponService.unredeemCoupon")
    void unredeemCouponEndpoint() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UnredeemCouponRequest request = new UnredeemCouponRequest("SAVE20", userId, orderId);

        mockMvc.perform(post("/pricing/coupons/unredeem")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(couponService).unredeemCoupon("SAVE20", userId, orderId);
    }

    @Test
    @DisplayName("POST /pricing/coupons/rollback-usage invokes couponService.rollbackUsage")
    void rollbackUsageEndpoint() throws Exception {
        UUID userId = UUID.randomUUID();
        RollbackCouponUsageRequest request = new RollbackCouponUsageRequest("SAVE20", userId);

        mockMvc.perform(post("/pricing/coupons/rollback-usage")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(couponService).rollbackUsage("SAVE20", userId);
    }
}
