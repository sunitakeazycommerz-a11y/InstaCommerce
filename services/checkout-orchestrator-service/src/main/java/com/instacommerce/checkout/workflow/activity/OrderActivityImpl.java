package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.OrderCreateRequest;
import com.instacommerce.checkout.dto.OrderCreationResult;
import com.instacommerce.checkout.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class OrderActivityImpl implements OrderActivity {
    private static final Logger log = LoggerFactory.getLogger(OrderActivityImpl.class);
    private final RestTemplate restTemplate;

    public OrderActivityImpl(@Qualifier("orderRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    @CircuitBreaker(name = "orderService", fallbackMethod = "createOrderFallback")
    public OrderCreationResult createOrder(OrderCreateRequest request) {
        log.info("Creating order for user={}", request.userId());
        return restTemplate.postForObject("/api/orders", request, OrderCreationResult.class);
    }

    @Override
    @CircuitBreaker(name = "orderService", fallbackMethod = "cancelOrderFallback")
    public void cancelOrder(String orderId) {
        log.info("Cancelling order={}", orderId);
        restTemplate.postForObject("/api/orders/{orderId}/cancel",
            Map.of("reason", "CHECKOUT_SAGA_ROLLBACK"), Void.class, orderId);
    }

    private OrderCreationResult createOrderFallback(OrderCreateRequest request, Exception e) {
        log.warn("Circuit breaker fallback for orderService createOrder userId={}: {}",
                request.userId(), e.getMessage());
        throw new ServiceUnavailableException("orderService",
                "Order service unavailable for createOrder", e);
    }

    private void cancelOrderFallback(String orderId, Exception e) {
        log.warn("Circuit breaker fallback for orderService cancelOrder orderId={}: {}",
                orderId, e.getMessage());
        throw new ServiceUnavailableException("orderService",
                "Order service unavailable for cancelOrder orderId=" + orderId, e);
    }
}
