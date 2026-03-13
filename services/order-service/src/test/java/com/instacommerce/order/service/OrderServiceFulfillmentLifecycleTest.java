package com.instacommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.instacommerce.order.domain.model.Order;
import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.exception.InvalidOrderStateException;
import com.instacommerce.order.repository.OrderRepository;
import com.instacommerce.order.repository.OrderStatusHistoryRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderServiceFulfillmentLifecycleTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusHistoryRepository statusHistoryRepository;

    @Mock
    private OutboxService outboxService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private com.instacommerce.order.client.PricingQuoteClient pricingQuoteClient;

    private OrderService orderService;
    private UUID orderId;
    private Order order;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, statusHistoryRepository, outboxService, auditLogService, pricingQuoteClient);
        orderId = UUID.randomUUID();
        order = new Order();
        order.setId(orderId);
        order.setUserId(UUID.randomUUID());
        order.setStatus(OrderStatus.PLACED);
        order.setCurrency("INR");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
    }

    @Test
    void advancesPackedEventThroughPackingAndPacked() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        orderService.advanceLifecycleFromFulfillment(orderId, OrderStatus.PACKED, "system:fulfillment-event", "order-packed");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PACKED);
        verify(orderRepository, org.mockito.Mockito.times(2)).save(order);
        verify(statusHistoryRepository, org.mockito.Mockito.times(2)).save(any());
        verify(outboxService, org.mockito.Mockito.times(2))
            .publish(eq("Order"), eq(orderId.toString()), eq("OrderStatusChanged"), any());
    }

    @Test
    void ignoresStaleFulfillmentEventWhenOrderAlreadyAdvanced() {
        order.setStatus(OrderStatus.OUT_FOR_DELIVERY);

        orderService.advanceLifecycleFromFulfillment(orderId, OrderStatus.PACKED, "system:fulfillment-event", "order-packed");

        verify(orderRepository, never()).save(any(Order.class));
        verifyNoInteractions(statusHistoryRepository, outboxService, auditLogService);
    }

    @Test
    void rejectsFulfillmentEventForPendingOrder() {
        order.setStatus(OrderStatus.PENDING);

        assertThatThrownBy(() -> orderService.advanceLifecycleFromFulfillment(
            orderId,
            OrderStatus.PACKED,
            "system:fulfillment-event",
            "order-packed"))
            .isInstanceOf(InvalidOrderStateException.class);

        verify(orderRepository, never()).save(any(Order.class));
        verifyNoInteractions(statusHistoryRepository, outboxService, auditLogService);
    }
}
