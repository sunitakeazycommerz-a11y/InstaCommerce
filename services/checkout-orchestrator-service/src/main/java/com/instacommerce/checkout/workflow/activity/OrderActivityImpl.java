package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.OrderCreateRequest;
import com.instacommerce.checkout.dto.OrderCreationResult;
import com.instacommerce.checkout.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.temporal.activity.Activity;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

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
        OrderCreateRequest effectiveRequest = withDeterministicIdempotencyKey(request);
        log.info("Creating order for user={} idempotencyKey={}",
            effectiveRequest.userId(), effectiveRequest.idempotencyKey());
        return restTemplate.postForObject("/api/orders", effectiveRequest, OrderCreationResult.class);
    }

    @Override
    @CircuitBreaker(name = "orderService", fallbackMethod = "cancelOrderFallback")
    public void cancelOrder(String orderId) {
        log.info("Cancelling order={}", orderId);
        restTemplate.postForObject("/api/orders/{orderId}/cancel",
            Map.of("reason", "CHECKOUT_SAGA_ROLLBACK"), Void.class, orderId);
    }

    @Override
    @CircuitBreaker(name = "orderService", fallbackMethod = "confirmOrderFallback")
    public void confirmOrder(String orderId, String idempotencyKey) {
        String effectiveKey = resolveConfirmIdempotencyKey(idempotencyKey);
        log.info("Confirming order={} status=PLACED idempotencyKey={}", orderId, effectiveKey);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (effectiveKey != null && !effectiveKey.isBlank()) {
            headers.set("Idempotency-Key", effectiveKey);
        }
        Map<String, String> body = Map.of(
            "status", "PLACED",
            "note", "Payment captured"
        );
        restTemplate.postForObject("/admin/orders/{orderId}/status",
            new HttpEntity<>(body, headers), Void.class, orderId);
    }

    private OrderCreateRequest withDeterministicIdempotencyKey(OrderCreateRequest request) {
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            return request;
        }
        String workflowId = Activity.getExecutionContext().getInfo().getWorkflowId();
        return request.withIdempotencyKey(workflowId + ":order:create");
    }

    private String resolveConfirmIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotencyKey;
        }
        return Activity.getExecutionContext().getInfo().getWorkflowId() + ":order:confirm";
    }

    @SuppressWarnings("unused")
    private OrderCreationResult createOrderFallback(OrderCreateRequest request, Exception e) {
        log.warn("Circuit breaker fallback for orderService createOrder userId={}: {}",
                request.userId(), e.getMessage());
        throw new ServiceUnavailableException("orderService",
                "Order service unavailable for createOrder", e);
    }

    @SuppressWarnings("unused")
    private void cancelOrderFallback(String orderId, Exception e) {
        log.warn("Circuit breaker fallback for orderService cancelOrder orderId={}: {}",
                orderId, e.getMessage());
        throw new ServiceUnavailableException("orderService",
                "Order service unavailable for cancelOrder orderId=" + orderId, e);
    }

    @SuppressWarnings("unused")
    private void confirmOrderFallback(String orderId, String idempotencyKey, Exception e) {
        log.warn("Circuit breaker fallback for orderService confirmOrder orderId={}: {}",
                orderId, e.getMessage());
        throw new ServiceUnavailableException("orderService",
                "Order service unavailable for confirmOrder orderId=" + orderId, e);
    }
}
