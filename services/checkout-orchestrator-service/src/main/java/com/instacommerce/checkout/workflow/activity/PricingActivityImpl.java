package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.PricingRequest;
import com.instacommerce.checkout.dto.PricingResult;
import com.instacommerce.checkout.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class PricingActivityImpl implements PricingActivity {
    private static final Logger log = LoggerFactory.getLogger(PricingActivityImpl.class);
    private final RestTemplate restTemplate;

    public PricingActivityImpl(@Qualifier("pricingRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    @CircuitBreaker(name = "pricingService", fallbackMethod = "calculatePriceFallback")
    public PricingResult calculatePrice(PricingRequest request) {
        log.info("Calculating price for user={}", request.userId());
        return restTemplate.postForObject("/pricing/calculate", request, PricingResult.class);
    }

    @Override
    @CircuitBreaker(name = "pricingService", fallbackMethod = "unredeemCouponFallback")
    public void unredeemCoupon(String code, String userId, String orderId) {
        log.info("Un-redeeming coupon code={} userId={} orderId={}", code, userId, orderId);
        restTemplate.postForObject("/pricing/coupons/unredeem",
            Map.of("code", code, "userId", userId, "orderId", orderId),
            Void.class);
    }

    @SuppressWarnings("unused")
    private PricingResult calculatePriceFallback(PricingRequest request, Exception e) {
        log.warn("Circuit breaker fallback for pricingService calculatePrice userId={}: {}",
                request.userId(), e.getMessage());
        throw new ServiceUnavailableException("pricingService",
                "Pricing service unavailable for calculatePrice", e);
    }

    @SuppressWarnings("unused")
    private void unredeemCouponFallback(String code, String userId, String orderId, Exception e) {
        log.warn("Circuit breaker fallback for pricingService unredeemCoupon code={} userId={} orderId={}: {}",
                code, userId, orderId, e.getMessage());
        throw new ServiceUnavailableException("pricingService",
                "Pricing service unavailable for unredeemCoupon code=" + code, e);
    }
}
