package com.instacommerce.fulfillment.client;

import com.instacommerce.fulfillment.config.FulfillmentProperties;
import com.instacommerce.fulfillment.exception.ServiceUnavailableException;
import com.instacommerce.fulfillment.security.InternalServiceAuthInterceptor;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class RestInventoryClient implements InventoryClient {
    private static final Logger logger = LoggerFactory.getLogger(RestInventoryClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public RestInventoryClient(FulfillmentProperties fulfillmentProperties,
                               @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                               @Value("${internal.service.token}") String serviceToken) {
        this(createRestTemplate(serviceName, serviceToken),
             fulfillmentProperties.getClients().getInventory().getBaseUrl());
    }

    RestInventoryClient(RestTemplate restTemplate, String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    private static RestTemplate createRestTemplate(String serviceName, String serviceToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        RestTemplate template = new RestTemplate(requestFactory);
        template.getInterceptors().add(new InternalServiceAuthInterceptor(serviceName, serviceToken));
        return template;
    }

    @Override
    @CircuitBreaker(name = "inventoryService", fallbackMethod = "releaseStockFallback")
    @Retry(name = "inventoryService")
    public void releaseStock(UUID productId, UUID storeId, int quantity, String reason, String referenceId) {
        requirePositiveQuantity(quantity, "releaseStock");
        InventoryAdjustRequest request = new InventoryAdjustRequest(productId, storeId, quantity, reason, referenceId);
        restTemplate.postForObject(baseUrl + "/inventory/adjust", request, Object.class);
    }

    @Override
    @CircuitBreaker(name = "inventoryService", fallbackMethod = "reserveOrDeductStockFallback")
    @Retry(name = "inventoryService")
    public void reserveOrDeductStock(UUID productId, UUID storeId, int quantity, String reason, String referenceId) {
        requirePositiveQuantity(quantity, "reserveOrDeductStock");
        InventoryAdjustRequest request = new InventoryAdjustRequest(productId, storeId, -quantity, reason, referenceId);
        restTemplate.postForObject(baseUrl + "/inventory/adjust", request, Object.class);
    }

    private static void requirePositiveQuantity(int quantity, String operation) {
        if (quantity <= 0) {
            throw new IllegalArgumentException(operation + " requires positive quantity, got: " + quantity);
        }
    }

    @SuppressWarnings("unused")
    private void releaseStockFallback(UUID productId, UUID storeId, int quantity, String reason,
                                      String referenceId, Exception e) {
        if (e instanceof IllegalArgumentException iae) {
            throw iae;
        }
        logger.warn("Circuit breaker fallback for inventoryService releaseStock product={} store={} qty={}: {}",
                productId, storeId, quantity, e.getMessage());
        throw new ServiceUnavailableException("inventoryService",
                "Inventory service unavailable for releaseStock product=" + productId, e);
    }

    @SuppressWarnings("unused")
    private void reserveOrDeductStockFallback(UUID productId, UUID storeId, int quantity, String reason,
                                              String referenceId, Exception e) {
        if (e instanceof IllegalArgumentException iae) {
            throw iae;
        }
        logger.warn("Circuit breaker fallback for inventoryService reserveOrDeductStock product={} store={} qty={}: {}",
                productId, storeId, quantity, e.getMessage());
        throw new ServiceUnavailableException("inventoryService",
                "Inventory service unavailable for reserveOrDeductStock product=" + productId, e);
    }
}
