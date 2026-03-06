package com.instacommerce.order.client;

import com.instacommerce.order.config.OrderProperties;
import com.instacommerce.order.dto.request.CartItem;
import com.instacommerce.order.exception.InsufficientStockException;
import com.instacommerce.order.security.InternalServiceAuthInterceptor;
import com.instacommerce.order.workflow.model.ReserveResult;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Component
public class RestInventoryClient implements InventoryClient {
    // TODO: Migrate from RestTemplate to WebClient for non-blocking I/O in Temporal activities
    private final RestTemplate restTemplate;
    private final String baseUrl;

    public RestInventoryClient(RestTemplateBuilder builder, OrderProperties orderProperties,
                               @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                               @Value("${internal.service.token:dev-internal-token-change-in-prod}") String serviceToken) {
        this.restTemplate = builder
            .setConnectTimeout(java.time.Duration.ofSeconds(2))
            .setReadTimeout(java.time.Duration.ofSeconds(5))
            .additionalInterceptors(new InternalServiceAuthInterceptor(serviceName, serviceToken))
            .build();
        this.baseUrl = orderProperties.getClients().getInventory().getBaseUrl();
    }

    @Override
    public ReserveResult reserveInventory(String idempotencyKey, String storeId, List<CartItem> items) {
        List<InventoryItemRequest> requestItems = items.stream()
            .map(item -> new InventoryItemRequest(item.productId(), item.quantity()))
            .toList();
        InventoryReserveRequest request = new InventoryReserveRequest(idempotencyKey, storeId, requestItems);
        try {
            InventoryReserveResponse response = restTemplate.postForObject(
                baseUrl + "/inventory/reserve", request, InventoryReserveResponse.class);
            if (response == null || response.reservationId() == null) {
                throw new InsufficientStockException("Inventory reservation failed");
            }
            return new ReserveResult(response.reservationId().toString(), response.expiresAt());
        } catch (HttpStatusCodeException ex) {
            if (Objects.equals(ex.getStatusCode(), HttpStatus.CONFLICT)) {
                throw new InsufficientStockException("Inventory reservation failed");
            }
            throw ex;
        }
    }

    @Override
    public void confirmReservation(String reservationId) {
        ReservationActionRequest request = new ReservationActionRequest(reservationId);
        restTemplate.postForLocation(baseUrl + "/inventory/confirm", request);
    }

    @Override
    public void cancelReservation(String reservationId) {
        ReservationActionRequest request = new ReservationActionRequest(reservationId);
        restTemplate.postForLocation(baseUrl + "/inventory/cancel", request);
    }
}
