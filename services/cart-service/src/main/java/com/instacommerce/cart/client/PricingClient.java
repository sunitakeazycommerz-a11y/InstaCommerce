package com.instacommerce.cart.client;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.instacommerce.cart.exception.ApiException;
import com.instacommerce.cart.security.InternalServiceAuthInterceptor;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Client for the pricing-service. Fetches authoritative product prices
 * to prevent client-side price manipulation.
 */
@Component
public class PricingClient {
    private static final Logger log = LoggerFactory.getLogger(PricingClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public PricingClient(@Value("${pricing-service.base-url:http://pricing-service:8089}") String baseUrl,
                         @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                         @Value("${internal.service.token}") String serviceToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(2000));
        requestFactory.setReadTimeout(Duration.ofMillis(3000));
        this.restTemplate = new RestTemplate(requestFactory);
        this.restTemplate.getInterceptors().add(new InternalServiceAuthInterceptor(serviceName, serviceToken));
        this.baseUrl = baseUrl;
    }

    /**
     * Fetches the current price for a product from the pricing-service.
     *
     * @param productId the product identifier
     * @return the pricing response containing the authoritative unit price
     * @throws ApiException if pricing-service is unavailable or returns an error
     */
    @CircuitBreaker(name = "pricingService", fallbackMethod = "getPriceFallback")
    @Retry(name = "pricingService")
    public PriceResponse getPrice(UUID productId) {
        try {
            PriceResponse response = restTemplate.getForObject(
                    baseUrl + "/pricing/products/{productId}", PriceResponse.class, productId);
            if (response == null) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PRICING_UNAVAILABLE",
                        "Pricing service returned null for product: " + productId);
            }
            return response;
        } catch (RestClientException ex) {
            log.warn("Pricing service call failed for product {}: {}", productId, ex.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PRICING_UNAVAILABLE",
                    "Unable to verify product price: " + ex.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private PriceResponse getPriceFallback(UUID productId, Exception e) {
        log.warn("Circuit breaker fallback for pricingService getPrice productId={}: {}", productId, e.getMessage());
        if (e instanceof ApiException apiEx) {
            throw apiEx;
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PRICING_UNAVAILABLE",
                "Unable to verify product price. Please try again later.");
    }

    /**
     * Response model for the pricing-service GET /pricing/products/{productId} endpoint.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PriceResponse(
        UUID productId,
        @JsonProperty("unitPriceCents")
        @JsonAlias({"unitPriceCents", "priceCents", "unit_price_cents", "price_cents"})
        long unitPriceCents,
        @JsonProperty("priceCents")
        @JsonAlias({"priceCents", "unitPriceCents", "price_cents", "unit_price_cents"})
        long priceCents,
        String currency
    ) {
        public PriceResponse(UUID productId, long unitPriceCents, String currency) {
            this(productId, unitPriceCents, unitPriceCents, currency);
        }
    }
}
