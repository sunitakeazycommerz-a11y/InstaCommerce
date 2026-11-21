package com.instacommerce.pricing.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.pricing.dto.request.RedeemPromotionRequest;
import com.instacommerce.pricing.dto.request.RollbackCouponUsageRequest;
import com.instacommerce.pricing.dto.request.RollbackPromotionUsageRequest;
import com.instacommerce.pricing.dto.request.UnredeemCouponRequest;
import com.instacommerce.pricing.dto.response.BatchPriceResponse;
import com.instacommerce.pricing.dto.response.PricedItem;
import com.instacommerce.pricing.service.CouponService;
import com.instacommerce.pricing.service.PricingService;
import com.instacommerce.pricing.service.PromotionService;
import com.instacommerce.pricing.service.QuoteTokenService;
import java.util.List;
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
    @Mock private PromotionService promotionService;

    @BeforeEach
    void setUp() {
        PricingController controller = new PricingController(
                pricingService, couponService, quoteTokenService, promotionService);
        PriceLookupController priceLookupController = new PriceLookupController(pricingService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller, priceLookupController).build();
    }

    @Test
    @DisplayName("GET /pricing/products/{id} aliases and GET /api/v1/prices/{id} return 200 while double-prefixed routes return 404")
    void productPriceRoutesAndNoDoublePrefixMapping() throws Exception {
        UUID productId = UUID.randomUUID();
        when(pricingService.calculatePrice(productId)).thenReturn(1450L);

        for (String validPath : List.of(
                "/pricing/products/" + productId,
                "/api/pricing/products/" + productId,
                "/api/v1/pricing/products/" + productId,
                "/api/v1/prices/" + productId)) {
            mockMvc.perform(get(validPath))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.productId").value(productId.toString()))
                    .andExpect(jsonPath("$.unitPriceCents").value(1450))
                    .andExpect(jsonPath("$.lineTotalCents").value(1450))
                    .andExpect(jsonPath("$.currency").value("INR"));
        }

        for (String bogusPath : List.of(
                "/pricing/api/v1/prices/" + productId,
                "/api/pricing/api/v1/prices/" + productId,
                "/api/v1/pricing/api/v1/prices/" + productId)) {
            mockMvc.perform(get(bogusPath))
                    .andExpect(status().isNotFound());
        }
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

    @Test
    @DisplayName("POST /pricing/promotions/redeem delegates to 4-arg recordPromotionUsage and returns 409 when rejected")
    void redeemPromotionEndpoint() throws Exception {
        UUID promotionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        RedeemPromotionRequest request = new RedeemPromotionRequest(promotionId, userId, orderId, 750L);

        when(promotionService.recordPromotionUsage(promotionId, userId, orderId, 750L)).thenReturn(true);
        mockMvc.perform(post("/pricing/promotions/redeem")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(promotionService).recordPromotionUsage(promotionId, userId, orderId, 750L);

        when(promotionService.recordPromotionUsage(promotionId, userId, orderId, 750L)).thenReturn(false);
        mockMvc.perform(post("/pricing/promotions/redeem")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /pricing/promotions/rollback delegates to 3-arg rollbackPromotionUsage")
    void rollbackPromotionEndpoint() throws Exception {
        UUID promotionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        RollbackPromotionUsageRequest request = new RollbackPromotionUsageRequest(promotionId, userId, orderId);

        mockMvc.perform(post("/pricing/promotions/rollback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(promotionService).rollbackPromotionUsage(promotionId, userId, orderId);
    }

    @Test
    @DisplayName("POST /pricing/products/batch returns active items and unavailableProductIds")
    void batchProductPricesEndpoint() throws Exception {
        UUID activeId = UUID.randomUUID();
        UUID inactiveId = UUID.randomUUID();
        List<UUID> productIds = List.of(activeId, inactiveId);
        when(pricingService.calculateBatchPrices(productIds)).thenReturn(new BatchPriceResponse(
                List.of(new PricedItem(activeId, 1200L, 1, 1200L, "INR")),
                List.of(inactiveId)));

        mockMvc.perform(post("/pricing/products/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(productIds)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productId").value(activeId.toString()))
                .andExpect(jsonPath("$.items[0].unitPriceCents").value(1200))
                .andExpect(jsonPath("$.unavailableProductIds[0]").value(inactiveId.toString()));
    }
}
