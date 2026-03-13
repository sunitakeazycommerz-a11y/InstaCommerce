package com.instacommerce.fulfillment.client;

import com.instacommerce.fulfillment.config.FulfillmentProperties;
import com.instacommerce.fulfillment.security.InternalServiceAuthInterceptor;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

@Component
public class RestInventoryClient implements InventoryClient {
    private static final Logger logger = LoggerFactory.getLogger(RestInventoryClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public RestInventoryClient(FulfillmentProperties fulfillmentProperties,
                               @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                               @Value("${internal.service.token}") String serviceToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restTemplate = new RestTemplate(requestFactory);
        this.restTemplate.getInterceptors().add(new InternalServiceAuthInterceptor(serviceName, serviceToken));
        this.baseUrl = fulfillmentProperties.getClients().getInventory().getBaseUrl();
    }

    @Override
    public void releaseStock(UUID productId, String storeId, int quantity, String reason, String referenceId) {
        InventoryAdjustRequest request = new InventoryAdjustRequest(productId, storeId, quantity, reason, referenceId);
        try {
            restTemplate.postForObject(baseUrl + "/inventory/adjust", request, Object.class);
        } catch (HttpClientErrorException | HttpServerErrorException ex) {
            logger.error("Inventory adjust HTTP error for product {} store {} qty {}: {} {}",
                productId, storeId, quantity, ex.getStatusCode(), ex.getMessage());
        } catch (Exception ex) {
            logger.error("Inventory adjust failed for product {} store {} qty {}: {}",
                productId, storeId, quantity, ex.getMessage());
        }
    }
}
