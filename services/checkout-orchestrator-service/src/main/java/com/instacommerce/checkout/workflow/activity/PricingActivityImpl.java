package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.PricingRequest;
import com.instacommerce.checkout.dto.PricingResult;
import com.instacommerce.checkout.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
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

    private PricingResult calculatePriceFallback(PricingRequest request, Exception e) {
        log.warn("Circuit breaker fallback for pricingService calculatePrice userId={}: {}",
                request.userId(), e.getMessage());
        throw new ServiceUnavailableException("pricingService",
                "Pricing service unavailable for calculatePrice", e);
    }
}
