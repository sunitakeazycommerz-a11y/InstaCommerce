package com.instacommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.order.client.PricingQuoteClient;
import com.instacommerce.order.consumer.FulfillmentOrderEvent;
import com.instacommerce.order.domain.model.Order;
import com.instacommerce.order.domain.model.OrderItem;
import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.dto.request.CartItem;
import com.instacommerce.order.exception.ApiException;
import com.instacommerce.order.repository.OrderRepository;
import com.instacommerce.order.repository.OrderStatusHistoryRepository;
import com.instacommerce.order.workflow.model.CreateOrderCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

class OrderWalletAndMetadataTest {

    private OrderRepository orderRepository;
    private OrderStatusHistoryRepository historyRepository;
    private OutboxService outboxService;
    private AuditLogService auditLogService;
    private PricingQuoteClient pricingQuoteClient;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        historyRepository = mock(OrderStatusHistoryRepository.class);
        outboxService = mock(OutboxService.class);
        auditLogService = mock(AuditLogService.class);
        pricingQuoteClient = mock(PricingQuoteClient.class);

        OrderCreationService creationService = new OrderCreationService(
            orderRepository, historyRepository, outboxService,
            pricingQuoteClient, mock(PlatformTransactionManager.class));
        OrderLifecycleService lifecycleService = new OrderLifecycleService(
            orderRepository, historyRepository, outboxService, auditLogService, null, null, null);
        OrderModificationService modificationService = new OrderModificationService(
            orderRepository, outboxService, auditLogService, lifecycleService);
        orderService = new OrderService(creationService, lifecycleService, modificationService);
    }

    @Test
    void rejectsNullTransactionManagerInsteadOfRunningWithoutTransaction() {
        assertThatThrownBy(() -> new OrderCreationService(
            orderRepository, historyRepository, outboxService, pricingQuoteClient, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("transactionManager");
    }

    @Test
    void persistsWalletAmountAndItemMetadataAndPublishesOrderPlacedItemFields() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        when(orderRepository.findByIdempotencyKey("idem-wallet-meta")).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId(orderId);
            }
            return o;
        });

        CartItem milk = new CartItem(
            productId, "Cold Milk", "MILK-1L", 2, 1500L, 3000L,
            true, true, "CHILLED", 1050);

        CreateOrderCommand command = CreateOrderCommand.builder()
            .orderId(orderId)
            .userId(userId)
            .storeId("store-blr-1")
            .items(List.of(milk))
            .subtotalCents(3000L)
            .discountCents(0L)
            .deliveryFeeCents(0L)
            .totalCents(3000L)
            .walletAmountCents(3000L)
            .currency("INR")
            .idempotencyKey("idem-wallet-meta")
            .deliveryAddress("42 Indiranagar")
            .build();

        String createdId = orderService.createOrder(command);
        assertThat(createdId).isEqualTo(orderId.toString());

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        Order persisted = orderCaptor.getValue();
        assertThat(persisted.getWalletAmountCents()).isEqualTo(3000L);
        assertThat(persisted.getPaymentId()).isNull();
        OrderItem savedItem = persisted.getItems().get(0);
        assertThat(savedItem.isColdChain()).isTrue();
        assertThat(savedItem.isFragile()).isTrue();
        assertThat(savedItem.getStorageTemperature()).isEqualTo("CHILLED");
        assertThat(savedItem.getTareWeightGrams()).isEqualTo(1050);

        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(persisted));
        orderService.updateOrderStatus(orderId, OrderStatus.PLACED, "system", "Wallet confirmed");

        ArgumentCaptor<Map<String, Object>> placedCaptor = ArgumentCaptor.captor();
        verify(outboxService).publish(eq("Order"), eq(orderId.toString()), eq("OrderPlaced"), placedCaptor.capture());
        Map<String, Object> placedPayload = placedCaptor.getValue();
        assertThat(placedPayload).doesNotContainKey("paymentId");
        List<?> items = (List<?>) placedPayload.get("items");
        assertThat(items).hasSize(1);
        Map<?, ?> firstItem = (Map<?, ?>) items.get(0);
        assertThat(firstItem.get("coldChain")).isEqualTo(true);
        assertThat(firstItem.get("fragile")).isEqualTo(true);
        assertThat(firstItem.get("storageTemperature")).isEqualTo("CHILLED");
        assertThat(firstItem.get("tareWeightGrams")).isEqualTo(1050);
    }

    @Test
    void rejectsWalletAmountExceedingOrderTotal() {
        when(orderRepository.findByIdempotencyKey("idem-bad-wallet")).thenReturn(Optional.empty());
        CreateOrderCommand command = CreateOrderCommand.builder()
            .userId(UUID.randomUUID())
            .storeId("store-1")
            .items(List.of(new CartItem(UUID.randomUUID(), 1, 1000L)))
            .subtotalCents(1000L)
            .totalCents(1000L)
            .walletAmountCents(1500L)
            .idempotencyKey("idem-bad-wallet")
            .build();

        assertThatThrownBy(() -> orderService.createOrder(command))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Wallet amount cannot exceed order total");
    }

    @Test
    void emitsWalletFieldsAcrossCancellationFailureRefundAndPartialRefundAndSetsReasonBeforeSave() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        Order cancelled = new Order();
        cancelled.setId(orderId);
        cancelled.setUserId(userId);
        cancelled.setPaymentId(paymentId);
        cancelled.setStatus(OrderStatus.PLACED);
        cancelled.setTotalCents(10_000L);
        cancelled.setWalletAmountCents(3_000L);
        cancelled.setCurrency("INR");

        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(cancelled));
        when(orderRepository.save(cancelled)).thenAnswer(inv -> {
            Order arg = inv.getArgument(0);
            assertThat(arg.getCancellationReason()).isEqualTo("Ops cancelled");
            return arg;
        });

        orderService.updateOrderStatus(orderId, OrderStatus.CANCELLED, "ops", "Ops cancelled");

        InOrder order = inOrder(orderRepository, outboxService);
        order.verify(orderRepository).save(cancelled);
        ArgumentCaptor<Map<String, Object>> cancelCaptor = ArgumentCaptor.captor();
        order.verify(outboxService).publish(eq("Order"), eq(orderId.toString()), eq("OrderCancelled"), cancelCaptor.capture());
        assertThat(cancelCaptor.getValue())
            .containsEntry("walletAmountCents", 3_000L)
            .containsEntry("walletRefundCents", 3_000L)
            .containsEntry("refundedCents", 10_000L);

        UUID failedOrderId = UUID.randomUUID();
        Order failed = new Order();
        failed.setId(failedOrderId);
        failed.setUserId(userId);
        failed.setStatus(OrderStatus.OUT_FOR_DELIVERY);
        failed.setTotalCents(2_500L);
        failed.setWalletAmountCents(2_500L);
        failed.setCurrency("INR");
        when(orderRepository.findByIdForUpdate(failedOrderId)).thenReturn(Optional.of(failed));
        when(orderRepository.save(failed)).thenReturn(failed);

        orderService.updateOrderStatus(failedOrderId, OrderStatus.FAILED, "fulfillment", "Delivery failed");
        ArgumentCaptor<Map<String, Object>> failCaptor = ArgumentCaptor.captor();
        verify(outboxService).publish(eq("Order"), eq(failedOrderId.toString()), eq("OrderFailed"), failCaptor.capture());
        assertThat(failCaptor.getValue())
            .doesNotContainKey("paymentId")
            .containsEntry("walletAmountCents", 2_500L)
            .containsEntry("walletRefundCents", 2_500L)
            .containsEntry("refundedCents", 2_500L);

        UUID partialOrderId = UUID.randomUUID();
        UUID prodId = UUID.randomUUID();
        Order partial = new Order();
        partial.setId(partialOrderId);
        partial.setUserId(userId);
        partial.setPaymentId(paymentId);
        partial.setStatus(OrderStatus.PLACED);
        partial.setSubtotalCents(10_000L);
        partial.setTotalCents(10_000L);
        partial.setWalletAmountCents(3_000L);
        OrderItem item = new OrderItem();
        item.setProductId(prodId);
        item.setProductName("Item");
        item.setProductSku("SKU-1");
        item.setQuantity(2);
        item.setUnitPriceCents(5_000L);
        item.setLineTotalCents(10_000L);
        partial.setItems(new ArrayList<>(List.of(item)));
        when(orderRepository.findByIdForUpdate(partialOrderId)).thenReturn(Optional.of(partial));
        when(orderRepository.save(partial)).thenReturn(partial);

        // Removing 1 qty -> 5000 refund fits within 7000 PSP portion, so walletRefundCents=0
        OrderService.PartialCancellationResult p1 = orderService.cancelOrderItems(
            partialOrderId, Map.of(prodId, 1), "Partial cancel", "user:" + userId, userId);
        assertThat(p1.refundCents()).isEqualTo(5_000L);
        assertThat(partial.getWalletAmountCents()).isEqualTo(3_000L);

        ArgumentCaptor<Map<String, Object>> partialCaptor = ArgumentCaptor.captor();
        verify(outboxService).publish(
            eq("Order"), eq(partialOrderId.toString()), eq("OrderPartiallyRefunded"), partialCaptor.capture());
        assertThat(partialCaptor.getValue())
            .containsEntry("walletAmountCents", 3_000L)
            .containsEntry("walletRefundCents", 0L)
            .containsEntry("pspRefundCents", 5_000L)
            .containsEntry("refundedCents", 5_000L);
    }

    @Test
    void applyOrderModificationComputesProRataDiscountedAndSplitTenderRefundDespiteGrossHint() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID milkId = UUID.randomUUID();
        UUID breadId = UUID.randomUUID();

        Order order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setPaymentId(paymentId);
        order.setStatus(OrderStatus.PACKED);
        order.setSubtotalCents(10_000L);
        order.setDiscountCents(2_000L);
        order.setTotalCents(8_000L);
        order.setWalletAmountCents(2_000L);
        order.setCurrency("INR");

        OrderItem milk = new OrderItem();
        milk.setProductId(milkId);
        milk.setProductName("Milk");
        milk.setProductSku("MILK-1");
        milk.setQuantity(2);
        milk.setUnitPriceCents(2_500L);
        milk.setLineTotalCents(5_000L);

        OrderItem bread = new OrderItem();
        bread.setProductId(breadId);
        bread.setProductName("Bread");
        bread.setProductSku("BREAD-1");
        bread.setQuantity(1);
        bread.setUnitPriceCents(5_000L);
        bread.setLineTotalCents(5_000L);

        order.setItems(new ArrayList<>(List.of(milk, bread)));
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        FulfillmentOrderEvent modified = new FulfillmentOrderEvent(
            orderId,
            milkId,
            2,
            5_000L,
            null,
            null,
            null
        );
        orderService.applyOrderModification(modified);

        // Removing 5000 gross out of 10000 subtotal with 8000 total -> pro-rata refund 4000 (not 5000 gross)
        // Previous total 8000 - remaining wallet 2000 = 6000 PSP capacity -> pspRefundCents=4000, walletRefundCents=0
        assertThat(order.getTotalCents()).isEqualTo(4_000L);
        assertThat(order.getDiscountCents()).isEqualTo(1_000L);
        assertThat(order.getWalletAmountCents()).isEqualTo(2_000L);

        ArgumentCaptor<Map<String, Object>> partialCaptor = ArgumentCaptor.captor();
        verify(outboxService).publish(
            eq("Order"), eq(orderId.toString()), eq("OrderPartiallyRefunded"), partialCaptor.capture());
        assertThat(partialCaptor.getValue())
            .containsEntry("refundCents", 4_000L)
            .containsEntry("refundedCents", 4_000L)
            .containsEntry("walletRefundCents", 0L)
            .containsEntry("pspRefundCents", 4_000L);
    }

    @Test
    void listOrdersSanitizesOversizedPageRequestViaPageableUtils() {
        UUID userId = UUID.randomUUID();
        when(orderRepository.findByUserId(eq(userId), any(Pageable.class))).thenReturn(Page.empty());

        orderService.listOrders(userId, PageRequest.of(0, 500));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findByUserId(eq(userId), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }
}
