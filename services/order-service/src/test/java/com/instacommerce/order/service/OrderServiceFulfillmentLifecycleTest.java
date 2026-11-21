package com.instacommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.instacommerce.order.client.PricingQuoteClient;
import com.instacommerce.order.consumer.FulfillmentOrderEvent;
import com.instacommerce.order.domain.model.Order;
import com.instacommerce.order.domain.model.OrderItem;
import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.exception.InvalidOrderStateException;
import com.instacommerce.order.repository.OrderRepository;
import com.instacommerce.order.repository.OrderStatusHistoryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

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
    private PricingQuoteClient pricingQuoteClient;

    private OrderService orderService;
    private UUID orderId;
    private Order order;

    @BeforeEach
    void setUp() {
        OrderCreationService creationService = new OrderCreationService(
            orderRepository, statusHistoryRepository, outboxService,
            pricingQuoteClient, mock(PlatformTransactionManager.class));
        OrderLifecycleService lifecycleService = new OrderLifecycleService(
            orderRepository, statusHistoryRepository, outboxService, auditLogService, null, null, null);
        OrderModificationService modificationService = new OrderModificationService(
            orderRepository, outboxService, auditLogService, lifecycleService);
        orderService = new OrderService(creationService, lifecycleService, modificationService);
        orderId = UUID.randomUUID();
        order = new Order();
        order.setId(orderId);
        order.setUserId(UUID.randomUUID());
        order.setStatus(OrderStatus.PLACED);
        order.setCurrency("INR");
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
    }

    @Test
    void advancesPackedEventThroughPackingAndPacked() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        orderService.advanceLifecycleFromFulfillment(orderId, OrderStatus.PACKED, "system:fulfillment-event", "order-packed");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PACKED);
        verify(orderRepository, times(2)).save(order);
        verify(statusHistoryRepository, times(2)).save(any());
        verify(outboxService, times(2))
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

    @Test
    void transitionsDirectlyToFailedWhenDeliveryFailedEventArrives() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        order.setStatus(OrderStatus.OUT_FOR_DELIVERY);

        orderService.advanceLifecycleFromFulfillment(
            orderId,
            OrderStatus.FAILED,
            "system:fulfillment-event",
            "delivery-failed");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        verify(orderRepository).save(order);
        verify(statusHistoryRepository).save(any());
        verify(auditLogService).log(any(), eq("ORDER_FAILED"), eq("Order"), eq(orderId.toString()), any());
        verify(outboxService).publish(eq("Order"), eq(orderId.toString()), eq("OrderStatusChanged"), any());
        verify(outboxService).publish(eq("Order"), eq(orderId.toString()), eq("OrderFailed"), any());
    }

    @Test
    void applyOrderModificationSkipsDuplicateVersionIdempotently() {
        order.setVersion(3L);
        FulfillmentOrderEvent duplicate = new FulfillmentOrderEvent(
            orderId, UUID.randomUUID(), 1, 500L, 3L, -500L, null);

        orderService.applyOrderModification(duplicate);

        verify(orderRepository, never()).save(any(Order.class));
        verifyNoInteractions(outboxService, auditLogService);
    }

    @Test
    void applyOrderModificationUpdatesItemsAndEmitsPartialRefundWhenPriceDeltaNegative() {
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        OrderItem itemA = new OrderItem();
        itemA.setProductId(productA);
        itemA.setQuantity(2);
        itemA.setUnitPriceCents(500L);
        itemA.setLineTotalCents(1000L);
        OrderItem itemB = new OrderItem();
        itemB.setProductId(productB);
        itemB.setQuantity(1);
        itemB.setUnitPriceCents(300L);
        itemB.setLineTotalCents(300L);
        order.setItems(new ArrayList<>(List.of(itemA, itemB)));
        order.setTotalCents(1300L);
        order.setPaymentId(UUID.randomUUID());
        order.setVersion(1L);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        FulfillmentOrderEvent event = new FulfillmentOrderEvent(
            orderId, null, null, null, 2L, -800L,
            List.of(
                new FulfillmentOrderEvent.OrderItemDelta(productA, -1, -500L),
                new FulfillmentOrderEvent.OrderItemDelta(productB, -1, -300L)
            ));

        orderService.applyOrderModification(event);

        assertThat(order.getTotalCents()).isEqualTo(500L);
        assertThat(order.getItems()).hasSize(1);
        assertThat(order.getItems().get(0).getProductId()).isEqualTo(productA);
        assertThat(order.getItems().get(0).getQuantity()).isEqualTo(1);
        assertThat(order.getItems().get(0).getLineTotalCents()).isEqualTo(500L);
        verify(outboxService).publish(eq("Order"), eq(orderId.toString()), eq("OrderModified"), any());
        verify(outboxService).publish(eq("Order"), eq(orderId.toString()), eq("OrderPartiallyRefunded"), any());
    }
}
