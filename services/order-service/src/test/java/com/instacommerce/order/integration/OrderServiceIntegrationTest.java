package com.instacommerce.order.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.instacommerce.order.OrderServiceApplication;
import com.instacommerce.order.client.PricingQuoteClient;
import com.instacommerce.order.domain.model.Order;
import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.dto.request.CartItem;
import com.instacommerce.order.repository.OrderRepository;
import com.instacommerce.order.service.OrderService;
import com.instacommerce.order.workflow.model.CreateOrderCommand;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link OrderService} proving order creation, status transitions,
 * and outbox event publishing against a real PostgreSQL instance with Flyway migrations.
 */
@SpringBootTest(
    classes = OrderServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class OrderServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean
    private PricingQuoteClient pricingQuoteClient;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
            TRUNCATE TABLE
                audit_log,
                outbox_events,
                order_status_history,
                order_items,
                orders,
                shedlock
            RESTART IDENTITY CASCADE
            """);
    }

    // -- shouldCreateOrder -------------------------------------------------------

    @Test
    void shouldCreateOrder() {
        CreateOrderCommand command = newCreateOrderCommand();

        String orderId = orderService.createOrder(command);

        assertThat(orderId).isNotNull();
        Order persisted = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(persisted.getUserId()).isEqualTo(command.getUserId());
        assertThat(persisted.getStoreId()).isEqualTo(command.getStoreId());
        assertThat(persisted.getTotalCents()).isEqualTo(command.getTotalCents());
        assertThat(persisted.getSubtotalCents()).isEqualTo(command.getSubtotalCents());
        assertThat(persisted.getDiscountCents()).isEqualTo(command.getDiscountCents());
        assertThat(persisted.getCurrency()).isEqualTo("INR");
        assertThat(persisted.getIdempotencyKey()).isEqualTo(command.getIdempotencyKey());
        assertThat(persisted.getItems()).hasSize(1);
        assertThat(persisted.getItems().get(0).getProductName()).isEqualTo("Milk 500ml");
        assertThat(persisted.getItems().get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    void shouldReturnExistingOrderOnDuplicateIdempotencyKey() {
        CreateOrderCommand command = newCreateOrderCommand();

        String firstId = orderService.createOrder(command);
        String secondId = orderService.createOrder(command);

        assertThat(secondId).isEqualTo(firstId);
        long count = orderRepository.count();
        assertThat(count).isEqualTo(1);
    }

    // -- shouldTransitionOrderStatus ---------------------------------------------

    @Test
    void shouldTransitionOrderStatus() {
        CreateOrderCommand command = newCreateOrderCommand();
        String orderId = orderService.createOrder(command);
        UUID orderUuid = UUID.fromString(orderId);

        // PENDING -> PLACED
        orderService.updateOrderStatus(orderUuid, OrderStatus.PLACED, "system", "Payment captured");
        Order afterPlaced = orderRepository.findById(orderUuid).orElseThrow();
        assertThat(afterPlaced.getStatus()).isEqualTo(OrderStatus.PLACED);

        // PLACED -> PACKING
        orderService.updateOrderStatus(orderUuid, OrderStatus.PACKING, "system:fulfillment", "Picker started");
        Order afterPacking = orderRepository.findById(orderUuid).orElseThrow();
        assertThat(afterPacking.getStatus()).isEqualTo(OrderStatus.PACKING);

        // PACKING -> PACKED
        orderService.updateOrderStatus(orderUuid, OrderStatus.PACKED, "system:fulfillment", "Order packed");
        Order afterPacked = orderRepository.findById(orderUuid).orElseThrow();
        assertThat(afterPacked.getStatus()).isEqualTo(OrderStatus.PACKED);

        // Verify status history records were written
        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM order_status_history WHERE order_id = ?",
            Integer.class, orderUuid);
        // Initial PENDING + PLACED + PACKING + PACKED = 4 transitions
        assertThat(historyCount).isEqualTo(4);
    }

    @Test
    void shouldCancelPendingOrder() {
        CreateOrderCommand command = newCreateOrderCommand();
        String orderId = orderService.createOrder(command);
        UUID orderUuid = UUID.fromString(orderId);

        orderService.cancelOrder(orderUuid, "User requested cancellation", "system");

        Order cancelled = orderRepository.findById(orderUuid).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getCancellationReason()).isEqualTo("User requested cancellation");
    }

    // -- shouldPublishOutboxEventOnOrderCreation ---------------------------------

    @Test
    void shouldPublishOutboxEventOnOrderCreation() {
        CreateOrderCommand command = newCreateOrderCommand();

        String orderId = orderService.createOrder(command);

        // Verify an outbox_events row was created in the same transaction
        List<Map<String, Object>> outboxRows = jdbcTemplate.queryForList(
            "SELECT aggregate_type, aggregate_id, event_type, payload, sent FROM outbox_events WHERE aggregate_id = ?",
            orderId);
        assertThat(outboxRows).isNotEmpty();

        Map<String, Object> createdEvent = outboxRows.stream()
            .filter(row -> "OrderCreated".equals(row.get("event_type")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected OrderCreated outbox event"));

        assertThat(createdEvent.get("aggregate_type")).isEqualTo("Order");
        assertThat(createdEvent.get("aggregate_id")).isEqualTo(orderId);
        assertThat((Boolean) createdEvent.get("sent")).isFalse();

        String payload = createdEvent.get("payload").toString();
        assertThat(payload).contains("orderId");
        assertThat(payload).contains("PENDING");
    }

    @Test
    void shouldPublishOutboxEventOnStatusTransition() {
        CreateOrderCommand command = newCreateOrderCommand();
        String orderId = orderService.createOrder(command);
        UUID orderUuid = UUID.fromString(orderId);

        orderService.updateOrderStatus(orderUuid, OrderStatus.PLACED, "system", "Payment captured");

        List<Map<String, Object>> outboxRows = jdbcTemplate.queryForList(
            "SELECT event_type FROM outbox_events WHERE aggregate_id = ?", orderId);

        List<String> eventTypes = outboxRows.stream()
            .map(row -> (String) row.get("event_type"))
            .toList();

        // OrderCreated + OrderStatusChanged + OrderPlaced
        assertThat(eventTypes).contains("OrderCreated", "OrderStatusChanged", "OrderPlaced");
    }

    // -- helpers -----------------------------------------------------------------

    private CreateOrderCommand newCreateOrderCommand() {
        UUID productId = UUID.randomUUID();
        CartItem item = new CartItem(productId, "Milk 500ml", "MILK-500", 2, 5000L, 10000L);
        return CreateOrderCommand.builder()
            .userId(UUID.randomUUID())
            .storeId("store-" + UUID.randomUUID().toString().substring(0, 8))
            .items(List.of(item))
            .subtotalCents(10000L)
            .discountCents(0L)
            .totalCents(10000L)
            .currency("INR")
            .idempotencyKey("order-key-" + UUID.randomUUID())
            .deliveryAddress("123 MG Road, Bangalore")
            .build();
    }
}
