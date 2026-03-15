package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class CouponActivityImpl implements CouponActivity {
    private static final Logger log = LoggerFactory.getLogger(CouponActivityImpl.class);
    private final RestTemplate restTemplate;

    public CouponActivityImpl(@Qualifier("pricingRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    @CircuitBreaker(name = "pricingService", fallbackMethod = "redeemCouponFallback")
    public void redeemCoupon(String code, String userId, String orderId, long discountCents) {
        log.info("Redeeming coupon code={} userId={} orderId={}", code, userId, orderId);
        restTemplate.postForObject("/pricing/coupons/redeem",
            Map.of("code", code, "userId", userId, "orderId", orderId, "discountCents", discountCents),
            Void.class);
    }

    @Override
    @CircuitBreaker(name = "pricingService", fallbackMethod = "unredeemCouponFallback")
    public void unredeemCoupon(String code, String userId, String orderId) {
        log.info("Un-redeeming coupon code={} userId={} orderId={}", code, userId, orderId);
        restTemplate.postForObject("/pricing/coupons/unredeem",
            Map.of("code", code, "userId", userId, "orderId", orderId),
            Void.class);
    }

    private void redeemCouponFallback(String code, String userId, String orderId, long discountCents, Exception e) {
        log.warn("Circuit breaker fallback for pricingService redeemCoupon code={} userId={}: {}",
                code, userId, e.getMessage());
        throw new ServiceUnavailableException("pricingService",
                "Pricing service unavailable for redeemCoupon code=" + code, e);
    }

    private void unredeemCouponFallback(String code, String userId, String orderId, Exception e) {
        log.warn("Circuit breaker fallback for pricingService unredeemCoupon code={} userId={}: {}",
                code, userId, e.getMessage());
        throw new ServiceUnavailableException("pricingService",
                "Pricing service unavailable for unredeemCoupon code=" + code, e);
    }
}
