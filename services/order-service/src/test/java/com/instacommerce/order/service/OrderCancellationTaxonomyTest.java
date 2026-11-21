package com.instacommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.contracts.orders.CancellationReason;
import com.instacommerce.order.client.PricingQuoteClient;
import com.instacommerce.order.domain.model.Order;
import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.exception.IllegalOrderStateTransitionException;
import com.instacommerce.order.exception.OrderAmendmentForbiddenException;
import com.instacommerce.order.repository.OrderRepository;
import com.instacommerce.order.repository.OrderStatusHistoryRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class OrderCancellationTaxonomyTest {

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
        OrderCreationService creationService = new OrderCreationService(
            orderRepository, statusHistoryRepository, outboxService,
            pricingQuoteClient, mock(PlatformTransactionManager.class));
        OrderLifecycleService lifecycleService = new OrderLifecycleService(
            orderRepository, statusHistoryRepository, outboxService, auditLogService, null, null, null);
        OrderModificationService modificationService = new OrderModificationService(
            orderRepository, outboxService, auditLogService, lifecycleService);
        orderService = new OrderService(creationService, lifecycleService, modificationService);
        orderId = UUID.randomUUID();
        userId = UUID.randomUUID();
        order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setStatus(OrderStatus.PLACED);
        order.setCurrency("INR");
        order.setTotalCents(2500L);
    }

    @Test
    @DisplayName("User cancellation emits OrderCancelled outbox event with CUSTOMER_REQUEST taxonomy")
    void userCancellationEmitsTaxonomy() {
        when(orderRepository.findByIdAndUserIdForUpdate(orderId, userId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.cancelOrderByUser(orderId, userId, "Customer changed their mind");

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.captor();
        verify(outboxService).publish(eq("Order"), eq(orderId.toString()), eq("OrderCancelled"), payloadCaptor.capture());

        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload.get("orderId")).isEqualTo(orderId);
        assertThat(payload.get("reasonCode")).isEqualTo(CancellationReason.CUSTOMER_REQUEST.name());
        assertThat(payload.get("reasonDescription")).isEqualTo("Customer changed their mind");
    }

    @Test
    @DisplayName("Admin/system cancellation with inventory shortage maps to INVENTORY_SHORTAGE reasonCode")
    void systemCancellationWithInventoryShortage() {
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.cancelOrder(orderId, "Dark store stock shortage for SKU-1234", "warehouse-service");

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.captor();
        verify(outboxService).publish(eq("Order"), eq(orderId.toString()), eq("OrderCancelled"), payloadCaptor.capture());

        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload.get("reasonCode")).isEqualTo(CancellationReason.INVENTORY_SHORTAGE.name());
        assertThat(payload.get("reasonDescription")).isEqualTo("Dark store stock shortage for SKU-1234");
    }

    @Test
    @DisplayName("Transitioning a cancelled order to any other status throws IllegalOrderStateTransitionException")
    void cancelledOrderCannotTransition() {
        order.setStatus(OrderStatus.CANCELLED);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.updateOrderStatus(orderId, OrderStatus.PACKING, "picker-1", "start packing"))
            .isInstanceOf(IllegalOrderStateTransitionException.class)
            .hasMessageContaining("Cannot transition order from CANCELLED to PACKING");
    }

    @Test
    @DisplayName("Order amendment is permitted when PLACED but rejected with 422 when PACKING")
    void orderAmendmentWindowValidation() {
        order.setStatus(OrderStatus.PLACED);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        // Permitted in PLACED state
        orderService.validateOrderAmendmentWindow(orderId);

        // Rejected once PACKING begins
        order.setStatus(OrderStatus.PACKING);
        assertThatThrownBy(() -> orderService.validateOrderAmendmentWindow(orderId))
            .isInstanceOf(OrderAmendmentForbiddenException.class)
            .satisfies(ex -> {
                OrderAmendmentForbiddenException forbidden = (OrderAmendmentForbiddenException) ex;
                assertThat(forbidden.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(forbidden.getCode()).isEqualTo("ORDER_ALREADY_PACKING");
            });
    }
}
