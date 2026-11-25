package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.exception.CheckoutException;
import com.instacommerce.checkout.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Component
public class PromotionActivityImpl implements PromotionActivity {
    private static final Logger log = LoggerFactory.getLogger(PromotionActivityImpl.class);
    private final RestTemplate restTemplate;

    public PromotionActivityImpl(@Qualifier("pricingRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    @CircuitBreaker(name = "pricingService", fallbackMethod = "redeemPromotionFallback")
    public void redeemPromotion(String promotionId, String userId, String orderId, long discountCents) {
        log.info("Redeeming promotion promotionId={} userId={} orderId={}", promotionId, userId, orderId);
        try {
            restTemplate.postForObject("/pricing/promotions/redeem",
                Map.of(
                    "promotionId", promotionId,
                    "userId", userId,
                    "orderId", orderId,
                    "discountCents", discountCents),
                Void.class);
        } catch (HttpClientErrorException ex) {
            throw new CheckoutException(
                "PROMOTION_LIMIT_EXCEEDED",
                "Promotion redemption rejected with status " + ex.getStatusCode().value(),
                HttpStatus.CONFLICT,
                ex);
        }
    }

    @Override
    @CircuitBreaker(name = "pricingService", fallbackMethod = "rollbackPromotionFallback")
    public void rollbackPromotion(String promotionId, String userId, String orderId) {
        log.info("Rolling back promotion promotionId={} userId={} orderId={}", promotionId, userId, orderId);
        restTemplate.postForObject("/pricing/promotions/rollback",
            Map.of("promotionId", promotionId, "userId", userId, "orderId", orderId),
            Void.class);
    }

    @SuppressWarnings("unused")
    private void redeemPromotionFallback(String promotionId, String userId, String orderId, long discountCents, Exception e) {
        if (e instanceof CheckoutException ce) {
            throw ce;
        }
        log.warn("Circuit breaker fallback for pricingService redeemPromotion promotionId={} userId={}: {}",
            promotionId, userId, e.getMessage());
        throw new ServiceUnavailableException("pricingService",
            "Pricing service unavailable for redeemPromotion promotionId=" + promotionId, e);
    }

    @SuppressWarnings("unused")
    private void rollbackPromotionFallback(String promotionId, String userId, String orderId, Exception e) {
        log.warn("Circuit breaker fallback for pricingService rollbackPromotion promotionId={} userId={}: {}",
            promotionId, userId, e.getMessage());
        throw new ServiceUnavailableException("pricingService",
            "Pricing service unavailable for rollbackPromotion promotionId=" + promotionId, e);
    }
}
