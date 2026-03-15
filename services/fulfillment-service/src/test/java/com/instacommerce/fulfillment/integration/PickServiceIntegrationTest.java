package com.instacommerce.fulfillment.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.instacommerce.fulfillment.FulfillmentServiceApplication;
import com.instacommerce.fulfillment.client.WarehouseClient;
import com.instacommerce.fulfillment.consumer.OrderPlacedItem;
import com.instacommerce.fulfillment.consumer.OrderPlacedPayload;
import com.instacommerce.fulfillment.domain.model.PickTask;
import com.instacommerce.fulfillment.domain.model.PickTaskStatus;
import com.instacommerce.fulfillment.repository.PickTaskRepository;
import com.instacommerce.fulfillment.service.PickService;
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
 * Integration tests for {@link PickService} proving pick task creation and outbox
 * event publishing against a real PostgreSQL instance with Flyway migrations.
 */
@SpringBootTest(
    classes = FulfillmentServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class PickServiceIntegrationTest {

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
    private WarehouseClient warehouseClient;

    @Autowired
    private PickService pickService;

    @Autowired
    private PickTaskRepository pickTaskRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
            TRUNCATE TABLE
                audit_log,
                outbox_events,
                pick_items,
                deliveries,
                pick_tasks,
                riders,
                shedlock
            RESTART IDENTITY CASCADE
            """);
    }

    // -- shouldCreatePickTask ----------------------------------------------------

    @Test
    void shouldCreatePickTask() {
        OrderPlacedPayload payload = newOrderPlacedPayload();

        pickService.createPickTask(payload);

        PickTask persisted = pickTaskRepository.findByOrderId(payload.orderId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(PickTaskStatus.PENDING);
        assertThat(persisted.getOrderId()).isEqualTo(payload.orderId());
        assertThat(persisted.getUserId()).isEqualTo(payload.userId());
        assertThat(persisted.getStoreId()).isEqualTo(payload.storeId());
        assertThat(persisted.getPaymentId()).isEqualTo(payload.paymentId());

        // Verify pick items were cascaded
        Integer itemCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pick_items WHERE pick_task_id = ?",
            Integer.class, persisted.getId());
        assertThat(itemCount).isEqualTo(2);
    }

    @Test
    void shouldBeIdempotentOnDuplicateOrderId() {
        OrderPlacedPayload payload = newOrderPlacedPayload();

        pickService.createPickTask(payload);
        pickService.createPickTask(payload);

        long taskCount = pickTaskRepository.count();
        assertThat(taskCount).isEqualTo(1);
    }

    // -- shouldPublishOutboxEventOnPickCompletion --------------------------------

    @Test
    void shouldPublishOutboxEventOnPickCompletion() {
        OrderPlacedPayload payload = newOrderPlacedPayload();
        pickService.createPickTask(payload);

        // Mark all items as PICKED via direct SQL so we can trigger completion via markPacked
        jdbcTemplate.update(
            "UPDATE pick_items SET status = 'PICKED', picked_qty = quantity WHERE pick_task_id = " +
            "(SELECT id FROM pick_tasks WHERE order_id = ?)", payload.orderId());

        UUID pickerId = UUID.randomUUID();
        pickService.markPacked(payload.orderId(), pickerId, "all items picked");

        // Verify pick task is now COMPLETED
        PickTask completedTask = pickTaskRepository.findByOrderId(payload.orderId()).orElseThrow();
        assertThat(completedTask.getStatus()).isEqualTo(PickTaskStatus.COMPLETED);
        assertThat(completedTask.getCompletedAt()).isNotNull();
        assertThat(completedTask.getPickerId()).isEqualTo(pickerId);

        // Verify outbox event was created for the pack completion
        List<Map<String, Object>> outboxRows = jdbcTemplate.queryForList(
            "SELECT aggregate_type, aggregate_id, event_type, payload, sent FROM outbox_events WHERE aggregate_id = ?",
            payload.orderId().toString());
        assertThat(outboxRows).isNotEmpty();

        Map<String, Object> packedEvent = outboxRows.stream()
            .filter(row -> "OrderPacked".equals(row.get("event_type")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected OrderPacked outbox event"));

        assertThat(packedEvent.get("aggregate_type")).isEqualTo("Fulfillment");
        assertThat(packedEvent.get("aggregate_id")).isEqualTo(payload.orderId().toString());
        assertThat((Boolean) packedEvent.get("sent")).isFalse();

        String eventPayload = packedEvent.get("payload").toString();
        assertThat(eventPayload).contains("orderId");
        assertThat(eventPayload).contains(payload.storeId());
    }

    // -- helpers -----------------------------------------------------------------

    private OrderPlacedPayload newOrderPlacedPayload() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String storeId = "store-" + UUID.randomUUID().toString().substring(0, 8);

        OrderPlacedItem item1 = new OrderPlacedItem(
            UUID.randomUUID(), "Milk 500ml", "MILK-500", 2, 5000L, 10000L);
        OrderPlacedItem item2 = new OrderPlacedItem(
            UUID.randomUUID(), "Bread Whole Wheat", "BREAD-WW", 1, 4500L, 4500L);

        return new OrderPlacedPayload(orderId, userId, storeId, paymentId, List.of(item1, item2));
    }
}
