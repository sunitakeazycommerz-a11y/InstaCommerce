package com.instacommerce.order.controller;

import com.instacommerce.order.dto.request.CancelOrderRequest;
import com.instacommerce.order.dto.request.CartItem;
import com.instacommerce.order.dto.request.OrderCreateRequest;
import com.instacommerce.order.dto.response.OrderCreationResult;
import com.instacommerce.order.service.OrderService;
import com.instacommerce.order.workflow.model.CreateOrderCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderControllerTest {

    @Test
    @DisplayName("createOrder converts OrderCreateRequest to command and returns OrderCreationResult")
    void shouldCreateOrder() {
        OrderService orderService = mock(OrderService.class);
        OrderController controller = new OrderController(orderService);

        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String expectedOrderId = UUID.randomUUID().toString();

        when(orderService.createOrder(any(CreateOrderCommand.class))).thenReturn(expectedOrderId);

        OrderCreateRequest request = new OrderCreateRequest(
            userId.toString(),
            "store_1",
            List.of(new CartItem(productId, 2, 500L)),
            1000L,
            0L,
            50L,
            1050L,
            "INR",
            "SAVE10",
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            "addr_1",
            "123 Main St",
            "pm_card",
            null,
            null,
            "idemp_key_1"
        );

        OrderCreationResult result = controller.createOrder(request);

        assertThat(result.orderId()).isEqualTo(expectedOrderId);
        assertThat(result.estimatedDeliveryMinutes()).isEqualTo(15);

        ArgumentCaptor<CreateOrderCommand> commandCaptor = ArgumentCaptor.forClass(CreateOrderCommand.class);
        verify(orderService).createOrder(commandCaptor.capture());
        CreateOrderCommand cmd = commandCaptor.getValue();
        assertThat(cmd.getUserId()).isEqualTo(userId);
        assertThat(cmd.getStoreId()).isEqualTo("store_1");
        assertThat(cmd.getTotalCents()).isEqualTo(1050L);
        assertThat(cmd.getDeliveryAddress()).isEqualTo("123 Main St");
        assertThat(cmd.getCouponCode()).isEqualTo("SAVE10");
    }

    @Test
    @DisplayName("cancelOrder for internal service calls orderService.cancelOrder with service principal")
    void shouldCancelOrderForInternalService() {
        OrderService orderService = mock(OrderService.class);
        OrderController controller = new OrderController(orderService);

        Authentication auth = new UsernamePasswordAuthenticationToken(
            "checkout-orchestrator-service", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE")));
        UUID orderId = UUID.randomUUID();
        CancelOrderRequest request = new CancelOrderRequest("CHECKOUT_SAGA_ROLLBACK");

        controller.cancelOrder(orderId, request, "checkout-orchestrator-service", auth);

        verify(orderService).cancelOrder(eq(orderId), eq("CHECKOUT_SAGA_ROLLBACK"), eq("checkout-orchestrator-service"), eq(null));
    }

    @Test
    @DisplayName("cancelOrder for regular user calls orderService.cancelOrderByUser with user UUID")
    void shouldCancelOrderForUser() {
        OrderService orderService = mock(OrderService.class);
        OrderController controller = new OrderController(orderService);

        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(
            userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        UUID orderId = UUID.randomUUID();
        CancelOrderRequest request = new CancelOrderRequest("Customer request");

        controller.cancelOrder(orderId, request, userId.toString(), auth);

        verify(orderService).cancelOrderByUser(eq(orderId), eq(userId), eq("Customer request"));
    }
}
