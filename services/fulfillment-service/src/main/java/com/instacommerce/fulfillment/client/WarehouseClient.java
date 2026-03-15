package com.instacommerce.fulfillment.client;

import com.instacommerce.fulfillment.config.FulfillmentProperties;
import com.instacommerce.fulfillment.security.InternalServiceAuthInterceptor;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class WarehouseClient {
    private static final Logger logger = LoggerFactory.getLogger(WarehouseClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public WarehouseClient(FulfillmentProperties fulfillmentProperties,
                           @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                           @Value("${internal.service.token}") String serviceToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restTemplate = new RestTemplate(requestFactory);
        this.restTemplate.getInterceptors().add(new InternalServiceAuthInterceptor(serviceName, serviceToken));
        this.baseUrl = fulfillmentProperties.getClients().getWarehouse().getBaseUrl();
    }

    @Cacheable(value = "storeCoordinates", key = "#storeId")
    @CircuitBreaker(name = "warehouseService", fallbackMethod = "getStoreCoordinatesFallback")
    @Retry(name = "warehouseService")
    @SuppressWarnings("unchecked")
    public StoreCoordinates getStoreCoordinates(String storeId) {
        Map<String, Object> body = restTemplate.getForObject(
                baseUrl + "/stores/{storeId}", Map.class, storeId);
        if (body == null) {
            logger.warn("Warehouse service returned null for store {}", storeId);
            return null;
        }
        Number lat = (Number) body.get("latitude");
        Number lng = (Number) body.get("longitude");
        if (lat == null || lng == null) {
            logger.warn("Store {} is missing latitude/longitude in warehouse response", storeId);
            return null;
        }
        return new StoreCoordinates(
                new java.math.BigDecimal(lat.toString()),
                new java.math.BigDecimal(lng.toString()));
    }

    private StoreCoordinates getStoreCoordinatesFallback(String storeId, Exception e) {
        logger.warn("Circuit breaker fallback for warehouseService getStoreCoordinates storeId={}: {}",
                storeId, e.getMessage());
        return null;
    }
}
