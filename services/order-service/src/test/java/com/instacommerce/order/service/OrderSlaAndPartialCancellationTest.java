package com.instacommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.order.client.PricingQuoteClient;
import com.instacommerce.order.config.OrderProperties;
import com.instacommerce.order.consumer.FulfillmentOrderEvent;
import com.instacommerce.order.domain.model.Order;
import com.instacommerce.order.domain.model.OrderItem;
import com.instacommerce.order.domain.model.OrderRefundCalculator;
import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.domain.statemachine.OrderStateMachine;
import com.instacommerce.order.exception.IllegalOrderStateTransitionException;
import com.instacommerce.order.repository.OrderRepository;
import com.instacommerce.order.repository.OrderStatusHistoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class OrderSlaAndPartialCancellationTest {

    private OrderRepository orderRepository;
    private OrderStatusHistoryRepository historyRepository;
    private OutboxService outboxService;
    private AuditLogService auditLogService;
    private PricingQuoteClient pricingQuoteClient;
    private AtomicReference<Instant> currentInstant;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        historyRepository = mock(OrderStatusHistoryRepository.class);
        outboxService = mock(OutboxService.class);
        auditLogService = mock(AuditLogService.class);
        pricingQuoteClient = mock(PricingQuoteClient.class);
        currentInstant = new AtomicReference<>(Instant.parse("2026-11-19T10:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return currentInstant.get();
            }
        };
        OrderProperties properties = new OrderProperties();
        properties.getSla().setPromisedDeliveryMinutes(15);
        OrderCreationService creationService = new OrderCreationService(
            orderRepository, historyRepository, outboxService,
            pricingQuoteClient, mock(PlatformTransactionManager.class));
        OrderLifecycleService lifecycleService = new OrderLifecycleService(
            orderRepository, historyRepository, outboxService, auditLogService, null, properties, clock);
        OrderModificationService modificationService = new OrderModificationService(
            orderRepository, outboxService, auditLogService, lifecycleService);
        orderService = new OrderService(creationService, lifecycleService, modificationService);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void calculatorHandlesProRataDiscountAndClampsToRemainingTotal() {
        OrderRefundCalculator.PartialRefundBreakdown first =
            OrderRefundCalculator.calculateForRemovedGross(2000L, 200L, 1900L, 500L);

        assertThat(first.discountAdjustmentCents()).isEqualTo(50L);
        assertThat(first.refundCents()).isEqualTo(450L);
        assertThat(first.updatedSubtotalCents()).isEqualTo(1500L);
        assertThat(first.updatedDiscountCents()).isEqualTo(150L);
        assertThat(first.updatedTotalCents()).isEqualTo(1450L);

        OrderRefundCalculator.PartialRefundBreakdown clamped =
            OrderRefundCalculator.calculateForRemovedGross(500L, 0L, 300L, 500L);
        assertThat(clamped.refundCents()).isEqualTo(300L);
        assertThat(clamped.updatedTotalCents()).isZero();
    }

    @Test
    void shortPickModificationWithoutExplicitPriceDeltaComputesProRataRefund() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();

        Order order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setPaymentId(UUID.randomUUID());
        order.setStatus(OrderStatus.PACKING);
        order.setSubtotalCents(2000L);
        order.setDiscountCents(200L);
        order.setTotalCents(1900L);
        order.setCurrency("INR");

        OrderItem itemA = new OrderItem();
        itemA.setProductId(productA);
        itemA.setProductName("Milk");
        itemA.setProductSku("SKU-A");
        itemA.setQuantity(2);
        itemA.setUnitPriceCents(500L);
        itemA.setLineTotalCents(1000L);

        OrderItem itemB = new OrderItem();
        itemB.setProductId(productB);
        itemB.setProductName("Bread");
        itemB.setProductSku("SKU-B");
        itemB.setQuantity(1);
        itemB.setUnitPriceCents(1000L);
        itemB.setLineTotalCents(1000L);
        order.setItems(List.of(itemA, itemB));

        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        orderService.applyOrderModification(new FulfillmentOrderEvent(orderId, productA, 1, null, null, null, null));

        assertThat(itemA.getQuantity()).isEqualTo(1);
        assertThat(itemA.getLineTotalCents()).isEqualTo(500L);
        assertThat(itemA.getPickedStatus()).isEqualTo("PARTIAL");
        assertThat(order.getSubtotalCents()).isEqualTo(1500L);
        assertThat(order.getDiscountCents()).isEqualTo(150L);
        assertThat(order.getTotalCents()).isEqualTo(1450L);
        verify(outboxService).publish(eq("Order"), eq(orderId.toString()), eq("OrderPartiallyRefunded"), any(Map.class));
    }

    @Test
    void partialItemCancellationRefundsSubsetAndCancelsOrderWhenAllItemsRemoved() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID prodA = UUID.randomUUID();
        UUID prodB = UUID.randomUUID();

        Order order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setPaymentId(UUID.randomUUID());
        order.setStatus(OrderStatus.PLACED);
        order.setSubtotalCents(2000L);
        order.setDiscountCents(0L);
        order.setTotalCents(2100L);
        order.setCurrency("INR");

        OrderItem itemA = new OrderItem();
        itemA.setProductId(prodA);
        itemA.setProductName("A");
        itemA.setProductSku("A");
        itemA.setQuantity(2);
        itemA.setUnitPriceCents(500L);
        itemA.setLineTotalCents(1000L);

        OrderItem itemB = new OrderItem();
        itemB.setProductId(prodB);
        itemB.setProductName("B");
        itemB.setProductSku("B");
        itemB.setQuantity(1);
        itemB.setUnitPriceCents(1000L);
        itemB.setLineTotalCents(1000L);
        order.setItems(List.of(itemA, itemB));

        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancelOrderItems(orderId, Map.of(prodA, 5), "bad qty", "admin", userId))
            .isInstanceOf(IllegalArgumentException.class);

        OrderService.PartialCancellationResult partial = orderService.cancelOrderItems(
            orderId, Map.of(prodA, 2), "Customer removed Milk", "user:" + userId, userId);

        assertThat(partial.fullOrderCancelled()).isFalse();
        assertThat(partial.refundCents()).isEqualTo(1000L);
        assertThat(partial.remainingTotalCents()).isEqualTo(1100L);
        assertThat(order.getItems()).hasSize(1);

        OrderService.PartialCancellationResult full = orderService.cancelOrderItems(
            orderId, Map.of(prodB, 1), "Customer removed remaining item", "user:" + userId, userId);

        assertThat(full.fullOrderCancelled()).isTrue();
        assertThat(full.refundCents()).isEqualTo(1100L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void terminalStateGuardsBlockPostTerminalTransitionsIncludingRefunded() {
        assertThat(OrderStatus.REFUNDED.isTerminal()).isTrue();
        for (OrderStatus terminal : List.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED, OrderStatus.FAILED, OrderStatus.REFUNDED)) {
            for (OrderStatus target : OrderStatus.values()) {
                assertThat(OrderStateMachine.canTransition(terminal, target)).isFalse();
            }
        }

        UUID orderId = UUID.randomUUID();
        Order delivered = new Order();
        delivered.setId(orderId);
        delivered.setUserId(UUID.randomUUID());
        delivered.setStatus(OrderStatus.DELIVERED);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(delivered));

        assertThatThrownBy(() -> orderService.updateOrderStatus(orderId, OrderStatus.REFUNDED, "admin", "post-delivery"))
            .isInstanceOf(IllegalOrderStateTransitionException.class);
        verify(outboxService, never()).publish(any(), any(), eq("OrderRefunded"), any());

        Order refunded = new Order();
        refunded.setId(orderId);
        refunded.setUserId(UUID.randomUUID());
        refunded.setStatus(OrderStatus.REFUNDED);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(refunded));

        assertThatThrownBy(() -> orderService.updateOrderStatus(orderId, OrderStatus.PACKING, "system", "retry"))
            .isInstanceOf(IllegalOrderStateTransitionException.class);
    }

    @Test
    void slaDeadlineIsStampedOnPlacedAndBreachTimestampRecordedOnceAcrossLifecycleAndSweep() {
        UUID orderId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(UUID.randomUUID());
        order.setStatus(OrderStatus.PENDING);
        order.setTotalCents(1500L);
        order.setCurrency("INR");
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        orderService.updateOrderStatus(orderId, OrderStatus.PLACED, "system", "Payment captured");
        assertThat(order.getSlaDeadlineAt()).isEqualTo(Instant.parse("2026-11-19T10:15:00Z"));
        assertThat(order.getSlaBreachedAt()).isNull();

        currentInstant.set(Instant.parse("2026-11-19T10:18:00Z"));
        orderService.updateOrderStatus(orderId, OrderStatus.PACKING, "fulfillment", "Picking started");
        assertThat(order.getSlaBreachedAt()).isEqualTo(Instant.parse("2026-11-19T10:18:00Z"));

        currentInstant.set(Instant.parse("2026-11-19T10:25:00Z"));
        orderService.updateOrderStatus(orderId, OrderStatus.PACKED, "fulfillment", "Packed");
        orderService.updateOrderStatus(orderId, OrderStatus.OUT_FOR_DELIVERY, "rider", "Dispatched");
        orderService.updateOrderStatus(orderId, OrderStatus.DELIVERED, "rider", "Delivered");

        assertThat(order.getDeliveredAt()).isEqualTo(Instant.parse("2026-11-19T10:25:00Z"));
        assertThat(order.getSlaBreachedAt()).isEqualTo(Instant.parse("2026-11-19T10:18:00Z"));
        verify(outboxService, times(1)).publish(eq("Order"), eq(orderId.toString()), eq("OrderSlaBreached"), any(Map.class));
        verify(outboxService, times(1)).publish(eq("Order"), eq(orderId.toString()), eq("OrderDelivered"), any(Map.class));

        Order overdueOrder = new Order();
        overdueOrder.setId(UUID.randomUUID());
        overdueOrder.setUserId(UUID.randomUUID());
        overdueOrder.setStatus(OrderStatus.OUT_FOR_DELIVERY);
        overdueOrder.setSlaDeadlineAt(Instant.parse("2026-11-19T10:10:00Z"));
        when(orderRepository.findUnrecordedSlaBreaches(any(), any(), any())).thenReturn(List.of(overdueOrder));

        int breached = orderService.checkAndRecordSlaBreaches();
        assertThat(breached).isEqualTo(1);
        assertThat(overdueOrder.getSlaBreachedAt()).isEqualTo(Instant.parse("2026-11-19T10:25:00Z"));
    }
}
