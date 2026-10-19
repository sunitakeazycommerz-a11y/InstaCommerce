package com.instacommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.order.client.PricingQuoteClient;
import com.instacommerce.order.domain.model.Order;
import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.exception.InvalidOrderStateException;
import com.instacommerce.order.repository.OrderRepository;
import com.instacommerce.order.repository.OrderStatusHistoryRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class OrderConcurrencyTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusHistoryRepository statusHistoryRepository;

    @Mock
    private OutboxService outboxService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private PricingQuoteClient pricingQuoteClient;

    private OrderService orderService;
    private UUID orderId;
    private UUID userId;
    private Order order;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, statusHistoryRepository, outboxService, auditLogService, pricingQuoteClient);
        orderId = UUID.randomUUID();
        userId = UUID.randomUUID();
        order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setStatus(OrderStatus.PLACED);
        order.setCurrency("INR");
        order.setTotalCents(1500L);
    }

    @Test
    @DisplayName("cancelOrderByUser acquires pessimistic lock and updates status to CANCELLED")
    void userCancellationAcquiresPessimisticLock() {
        when(orderRepository.findByIdAndUserIdForUpdate(orderId, userId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.cancelOrderByUser(orderId, userId, "Customer changed mind");

        verify(orderRepository).findByIdAndUserIdForUpdate(orderId, userId);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo("Customer changed mind");
        verify(outboxService).publish(eq("Order"), eq(orderId.toString()), eq("OrderCancelled"), any());
    }

    @Test
    @DisplayName("cancelOrderByUser throws Conflict (409) when order is already in PACKING state")
    void userCancellationFailsWithConflictWhenPacking() {
        order.setStatus(OrderStatus.PACKING);
        when(orderRepository.findByIdAndUserIdForUpdate(orderId, userId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancelOrderByUser(orderId, userId, "Late cancel"))
            .isInstanceOf(InvalidOrderStateException.class)
            .satisfies(ex -> {
                InvalidOrderStateException exception = (InvalidOrderStateException) ex;
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(exception.getCode()).isEqualTo("INVALID_ORDER_STATE");
            });

        verify(orderRepository, never()).save(any());
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("cancelOrderByUser is idempotent if order already cancelled")
    void userCancellationIsIdempotent() {
        order.setStatus(OrderStatus.CANCELLED);
        when(orderRepository.findByIdAndUserIdForUpdate(orderId, userId)).thenReturn(Optional.of(order));

        orderService.cancelOrderByUser(orderId, userId, "Duplicate cancel");

        verify(orderRepository, never()).save(any());
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("cancelOrder acquires pessimistic lock with findByIdForUpdate")
    void systemCancelAcquiresPessimisticLock() {
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.cancelOrder(orderId, "Admin intervention", "admin:123", UUID.randomUUID());

        verify(orderRepository).findByIdForUpdate(orderId);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(outboxService).publish(eq("Order"), eq(orderId.toString()), eq("OrderCancelled"), any());
    }

    @Test
    @DisplayName("updateOrderStatus acquires pessimistic lock with findByIdForUpdate")
    void updateOrderStatusAcquiresPessimisticLock() {
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.updateOrderStatus(orderId, OrderStatus.PACKING, "warehouse:robot", "Packing started");

        verify(orderRepository).findByIdForUpdate(orderId);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PACKING);
        verify(outboxService).publish(eq("Order"), eq(orderId.toString()), eq("OrderStatusChanged"), any());
    }
}
