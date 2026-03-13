package com.instacommerce.fulfillment.client;

import com.instacommerce.fulfillment.config.FulfillmentProperties;
import com.instacommerce.fulfillment.security.InternalServiceAuthInterceptor;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

@Component
public class WarehouseClient {
    private static final Logger logger = LoggerFactory.getLogger(WarehouseClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public WarehouseClient(FulfillmentProperties fulfillmentProperties,
                           @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                           @Value("${internal.service.token:dev-internal-token-change-in-prod}") String serviceToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restTemplate = new RestTemplate(requestFactory);
        this.restTemplate.getInterceptors().add(new InternalServiceAuthInterceptor(serviceName, serviceToken));
        this.baseUrl = fulfillmentProperties.getClients().getWarehouse().getBaseUrl();
    }

    @Cacheable(value = "storeCoordinates", key = "#storeId")
    @SuppressWarnings("unchecked")
    public StoreCoordinates getStoreCoordinates(String storeId) {
        try {
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
        } catch (HttpClientErrorException | HttpServerErrorException ex) {
            logger.error("Warehouse HTTP error fetching store {}: {} {}",
                    storeId, ex.getStatusCode(), ex.getMessage());
            return null;
        } catch (Exception ex) {
            logger.error("Warehouse client failed fetching store {}: {}", storeId, ex.getMessage());
            return null;
        }
    }
}
