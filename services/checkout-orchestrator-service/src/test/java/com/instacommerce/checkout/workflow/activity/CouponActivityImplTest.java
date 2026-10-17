package com.instacommerce.checkout.workflow.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

class CouponActivityImplTest {

    private RestTemplate restTemplate;
    private CouponActivityImpl activity;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        activity = new CouponActivityImpl(restTemplate);
    }

    @Test
    @DisplayName("redeemCoupon posts correct payload to /pricing/coupons/redeem")
    void redeemCouponPostsPayload() {
        activity.redeemCoupon("SAVE20", "user-1", "order-1", 1500L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(restTemplate).postForObject(eq("/pricing/coupons/redeem"), captor.capture(), eq(Void.class));

        Map<String, Object> body = captor.getValue();
        assertEquals("SAVE20", body.get("code"));
        assertEquals("user-1", body.get("userId"));
        assertEquals("order-1", body.get("orderId"));
        assertEquals(1500L, body.get("discountCents"));
    }

    @Test
    @DisplayName("unredeemCoupon posts correct payload to /pricing/coupons/unredeem")
    void unredeemCouponPostsPayload() {
        activity.unredeemCoupon("SAVE20", "user-1", "order-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(restTemplate).postForObject(eq("/pricing/coupons/unredeem"), captor.capture(), eq(Void.class));

        Map<String, Object> body = captor.getValue();
        assertEquals("SAVE20", body.get("code"));
        assertEquals("user-1", body.get("userId"));
        assertEquals("order-1", body.get("orderId"));
    }

    @Test
    @DisplayName("rollbackUsage posts correct payload to /pricing/coupons/rollback-usage")
    void rollbackUsagePostsPayload() {
        activity.rollbackUsage("SAVE20", "user-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(restTemplate).postForObject(eq("/pricing/coupons/rollback-usage"), captor.capture(), eq(Void.class));

        Map<String, Object> body = captor.getValue();
        assertEquals("SAVE20", body.get("code"));
        assertEquals("user-1", body.get("userId"));
    }
}
