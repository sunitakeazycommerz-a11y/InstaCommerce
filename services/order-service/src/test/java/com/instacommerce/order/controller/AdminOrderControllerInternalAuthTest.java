package com.instacommerce.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.dto.request.CancelOrderRequest;
import com.instacommerce.order.dto.request.OrderStatusUpdateRequest;
import com.instacommerce.order.dto.response.OrderResponse;
import com.instacommerce.order.service.OrderService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminOrderControllerInternalAuthTest {

    private OrderService orderService;
    private AdminOrderController controller;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        controller = new AdminOrderController(orderService);
    }

    @Test
    @DisplayName("updateStatus with service principal does not throw IllegalArgumentException and passes null userId")
    void shouldUpdateStatusWithInternalServicePrincipal() {
        UUID orderId = UUID.randomUUID();
        OrderStatusUpdateRequest request = new OrderStatusUpdateRequest(OrderStatus.DELIVERED, "Delivered by fleet");

        controller.updateStatus(orderId, request, "fulfillment-service");

        verify(orderService).updateOrderStatus(
            orderId,
            OrderStatus.DELIVERED,
            "admin:fulfillment-service",
            "Delivered by fleet",
            null
        );
    }

    @Test
    @DisplayName("cancelOrder with service principal passes null userId without exception")
    void shouldCancelOrderWithInternalServicePrincipal() {
        UUID orderId = UUID.randomUUID();
        CancelOrderRequest request = new CancelOrderRequest("Customer requested cancellation");

        controller.cancelOrder(orderId, request, "notification-service");

        verify(orderService).cancelOrder(
            orderId,
            "Customer requested cancellation",
            "admin:notification-service",
            null
        );
    }

    @Test
    @DisplayName("cancelOrder with UUID principal correctly converts principal to UUID")
    void shouldCancelOrderWithUserPrincipal() {
        UUID orderId = UUID.randomUUID();
        UUID adminUserId = UUID.randomUUID();
        CancelOrderRequest request = new CancelOrderRequest("Fraud suspect");

        controller.cancelOrder(orderId, request, adminUserId.toString());

        verify(orderService).cancelOrder(
            orderId,
            "Fraud suspect",
            "admin:" + adminUserId,
            adminUserId
        );
    }

    @Test
    @DisplayName("getOrder delegates to orderService with bypassOwnershipCheck true")
    void shouldGetOrder() {
        UUID orderId = UUID.randomUUID();
        OrderResponse expected = mock(OrderResponse.class);
        when(orderService.getOrder(orderId, null, true)).thenReturn(expected);

        OrderResponse actual = controller.getOrder(orderId);

        assertThat(actual).isSameAs(expected);
        verify(orderService).getOrder(orderId, null, true);
    }
}
