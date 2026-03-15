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
public class RestOrderClient implements OrderClient {
    private static final Logger logger = LoggerFactory.getLogger(RestOrderClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public RestOrderClient(FulfillmentProperties fulfillmentProperties,
                           @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                           @Value("${internal.service.token}") String serviceToken) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restTemplate = new RestTemplate(requestFactory);
        this.restTemplate.getInterceptors().add(new InternalServiceAuthInterceptor(serviceName, serviceToken));
        this.baseUrl = fulfillmentProperties.getClients().getOrder().getBaseUrl();
    }

    @Override
    @CircuitBreaker(name = "orderService", fallbackMethod = "updateStatusFallback")
    @Retry(name = "orderService")
    public void updateStatus(UUID orderId, String status, String note) {
        OrderStatusUpdateRequest request = new OrderStatusUpdateRequest(status, note);
        restTemplate.postForLocation(baseUrl + "/admin/orders/" + orderId + "/status", request);
    }

    private void updateStatusFallback(UUID orderId, String status, String note, Exception e) {
        logger.warn("Circuit breaker fallback for orderService updateStatus orderId={} status={}: {}",
                orderId, status, e.getMessage());
        throw new ServiceUnavailableException("orderService",
                "Order service unavailable for updateStatus orderId=" + orderId, e);
    }
}
