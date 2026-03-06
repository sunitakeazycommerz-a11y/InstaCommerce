package com.instacommerce.cart.client;

import com.instacommerce.cart.exception.ApiException;
import com.instacommerce.cart.security.InternalServiceAuthInterceptor;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
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

    public PricingClient(RestTemplateBuilder builder,
                         @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                         @Value("${internal.service.token:dev-internal-token-change-in-prod}") String serviceToken) {
        this.restTemplate = builder
                .rootUri("http://pricing-service:8087")
                .setConnectTimeout(java.time.Duration.ofMillis(2000))
                .setReadTimeout(java.time.Duration.ofMillis(3000))
                .additionalInterceptors(new InternalServiceAuthInterceptor(serviceName, serviceToken))
                .build();
    }

    /**
     * Fetches the current price for a product from the pricing-service.
     *
     * @param productId the product identifier
     * @return the pricing response containing the authoritative unit price
     * @throws ApiException if pricing-service is unavailable or returns an error
     */
    public PriceResponse getPrice(UUID productId) {
        try {
            PriceResponse response = restTemplate.getForObject(
                    "/api/v1/prices/{productId}", PriceResponse.class, productId);
            if (response == null) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PRICING_UNAVAILABLE",
                        "Pricing service returned null for product: " + productId);
            }
            return response;
        } catch (RestClientException ex) {
            log.error("Failed to fetch price for product={}: {}", productId, ex.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PRICING_UNAVAILABLE",
                    "Unable to verify product price. Please try again later.");
        }
    }

    /**
     * Response model for the pricing-service GET /api/v1/prices/{productId} endpoint.
     */
    public record PriceResponse(UUID productId, long unitPriceCents, String currency) {
    }
}

