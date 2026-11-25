package com.instacommerce.checkout.workflow.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.checkout.exception.CheckoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

class PromotionActivityImplTest {

    private RestTemplate restTemplate;
    private PromotionActivityImpl activity;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        activity = new PromotionActivityImpl(restTemplate);
    }

    @Test
    @DisplayName("redeemPromotion posts payload to /pricing/promotions/redeem")
    void redeemPromotionPostsPayload() {
        activity.redeemPromotion("promo-1", "user-1", "order-1", 1000L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(restTemplate).postForObject(eq("/pricing/promotions/redeem"), captor.capture(), eq(Void.class));

        Map<String, Object> body = captor.getValue();
        assertThat(body).containsEntry("promotionId", "promo-1")
            .containsEntry("userId", "user-1")
            .containsEntry("orderId", "order-1")
            .containsEntry("discountCents", 1000L);
    }

    @Test
    @DisplayName("redeemPromotion converts 409 limit conflict into non-retryable CheckoutException")
    void redeemPromotionMapsConflictToCheckoutException() {
        when(restTemplate.postForObject(eq("/pricing/promotions/redeem"), any(), eq(Void.class)))
            .thenThrow(HttpClientErrorException.create(
                HttpStatus.CONFLICT, "Conflict", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

        assertThatThrownBy(() -> activity.redeemPromotion("promo-1", "user-1", "order-1", 1000L))
            .isInstanceOf(CheckoutException.class)
            .satisfies(ex -> assertThat(((CheckoutException) ex).getCode()).isEqualTo("PROMOTION_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("rollbackPromotion posts payload to /pricing/promotions/rollback")
    void rollbackPromotionPostsPayload() {
        activity.rollbackPromotion("promo-1", "user-1", "order-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(restTemplate).postForObject(eq("/pricing/promotions/rollback"), captor.capture(), eq(Void.class));

        assertThat(captor.getValue()).containsEntry("promotionId", "promo-1")
            .containsEntry("userId", "user-1")
            .containsEntry("orderId", "order-1");
    }
}
