package com.instacommerce.order.client;

import com.instacommerce.order.config.OrderProperties;
import com.instacommerce.order.dto.request.CartItem;
import com.instacommerce.order.exception.InsufficientStockException;
import com.instacommerce.order.security.InternalServiceAuthInterceptor;
import com.instacommerce.order.workflow.model.ReserveResult;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestInventoryClient implements InventoryClient {
    private final RestClient restClient;
    private final String baseUrl;

    public RestInventoryClient(RestClient.Builder builder, OrderProperties orderProperties,
                               @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                               @Value("${internal.service.token:dev-internal-token-change-in-prod}") String serviceToken) {
        this.restClient = builder
            .requestFactory(clientHttpRequestFactory(Duration.ofSeconds(2), Duration.ofSeconds(5)))
            .requestInterceptor(new InternalServiceAuthInterceptor(serviceName, serviceToken))
            .build();
        this.baseUrl = orderProperties.getClients().getInventory().getBaseUrl();
    }

    @Override
    public ReserveResult reserveInventory(String idempotencyKey, String storeId, List<CartItem> items) {
        List<InventoryItemRequest> requestItems = items.stream()
            .map(item -> new InventoryItemRequest(item.productId(), item.quantity()))
            .toList();
        InventoryReserveRequest request = new InventoryReserveRequest(idempotencyKey, storeId, requestItems);
        InventoryReserveResponse response = restClient.post()
            .uri(baseUrl + "/inventory/reserve")
            .body(request)
            .retrieve()
            .onStatus(status -> status.value() == 409,
                (clientRequest, clientResponse) -> {
                    throw new InsufficientStockException("Inventory reservation failed");
                })
            .body(InventoryReserveResponse.class);
        if (response == null || response.reservationId() == null) {
            throw new InsufficientStockException("Inventory reservation failed");
        }
        return new ReserveResult(response.reservationId().toString(), response.expiresAt());
    }

    @Override
    public void confirmReservation(String reservationId) {
        ReservationActionRequest request = new ReservationActionRequest(reservationId);
        restClient.post()
            .uri(baseUrl + "/inventory/confirm")
            .body(request)
            .retrieve()
            .toBodilessEntity();
    }

    @Override
    public void cancelReservation(String reservationId) {
        ReservationActionRequest request = new ReservationActionRequest(reservationId);
        restClient.post()
            .uri(baseUrl + "/inventory/cancel")
            .body(request)
            .retrieve()
            .toBodilessEntity();
    }

    private SimpleClientHttpRequestFactory clientHttpRequestFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
