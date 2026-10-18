package com.instacommerce.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instacommerce.inventory.InventoryServiceApplication;
import com.instacommerce.inventory.client.WarehouseClient;
import com.instacommerce.inventory.domain.model.StockItem;
import com.instacommerce.inventory.dto.request.InventoryItemRequest;
import com.instacommerce.inventory.dto.request.ReserveRequest;
import com.instacommerce.inventory.exception.StockFrozenException;
import com.instacommerce.inventory.repository.OutboxEventRepository;
import com.instacommerce.inventory.repository.StockItemRepository;
import com.instacommerce.inventory.service.ReservationService;
import com.instacommerce.inventory.service.StockReconciliationService;
import com.instacommerce.inventory.service.StockReconciliationService.MissingItemAlert;
import com.instacommerce.inventory.service.StockReconciliationService.PhysicalStockCount;
import com.instacommerce.inventory.service.StockReconciliationService.ReconciliationSummary;
import java.util.List;
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

@SpringBootTest(
    classes = InventoryServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class StockReconciliationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @org.junit.jupiter.api.BeforeAll
    static void runMigrations() {
        POSTGRES.start();
        org.flywaydb.core.Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    @MockitoBean
    private WarehouseClient warehouseClient;

    @Autowired
    private StockReconciliationService reconciliationService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private StockItemRepository stockItemRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
            TRUNCATE TABLE
                outbox_events,
                reservation_line_items,
                reservations,
                stock_adjustment_log,
                audit_log,
                stock_items,
                shedlock
            RESTART IDENTITY CASCADE
            """);
    }

    @Test
    void shouldDetectStockDriftAndEmitEvent() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        // System stock: on_hand = 50, reserved = 10 -> total = 60
        StockItem stock = new StockItem();
        stock.setProductId(productId);
        stock.setStoreId(storeId);
        stock.setOnHand(50);
        stock.setReserved(10);
        stockItemRepository.save(stock);

        // Physical count: 55 units on shelf (drift = -5 units)
        List<PhysicalStockCount> physicalCounts = List.of(
            new PhysicalStockCount(productId, 55)
        );

        ReconciliationSummary summary = reconciliationService.reconcileStock(storeId, physicalCounts);

        assertThat(summary.totalAudited()).isEqualTo(1);
        assertThat(summary.discrepanciesFound()).isEqualTo(1);
        assertThat(summary.discrepancies().get(0).driftQuantity()).isEqualTo(-5);
        assertThat(summary.discrepancies().get(0).systemStock()).isEqualTo(60);
        assertThat(summary.discrepancies().get(0).physicalStock()).isEqualTo(55);

        // Verify StockDriftDetected outbox event was emitted
        boolean eventFound = outboxEventRepository.findAll().stream()
            .anyMatch(e -> "StockDriftDetected".equals(e.getEventType()) && productId.toString().equals(e.getAggregateId()));
        assertThat(eventFound).isTrue();
    }

    @Test
    void shouldFreezeStockOnMissingItemAndBlockReservations() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        StockItem stock = new StockItem();
        stock.setProductId(productId);
        stock.setStoreId(storeId);
        stock.setOnHand(20);
        stock.setReserved(0);
        stockItemRepository.save(stock);

        // Picker reports missing item
        MissingItemAlert alert = new MissingItemAlert(
            productId,
            storeId,
            2,
            "picker-john-42",
            "Aisle-3-Shelf-B"
        );

        var task = reconciliationService.reportMissingItem(alert);
        assertThat(task).isNotNull();
        assertThat(task.urgency()).isEqualTo("CRITICAL");

        // Verify stock is now frozen
        StockItem updatedStock = stockItemRepository.findByProductIdAndStoreId(productId, storeId).orElseThrow();
        assertThat(updatedStock.isFrozen()).isTrue();

        // Verify DarkStoreCycleCountTaskCreated event was emitted
        boolean taskEventFound = outboxEventRepository.findAll().stream()
            .anyMatch(e -> "DarkStoreCycleCountTaskCreated".equals(e.getEventType()));
        assertThat(taskEventFound).isTrue();

        // Attempting to reserve this frozen SKU must be rejected with StockFrozenException
        ReserveRequest request = new ReserveRequest(
            "frozen-reserve-" + UUID.randomUUID(),
            storeId,
            List.of(new InventoryItemRequest(productId, 5))
        );

        assertThatThrownBy(() -> reservationService.reserve(request))
            .isInstanceOf(StockFrozenException.class);

        // Now cycle count audit verifies and unfreezes stock with 18 physical units
        reconciliationService.unfreezeStock(productId, storeId, 18);

        StockItem unfrozenStock = stockItemRepository.findByProductIdAndStoreId(productId, storeId).orElseThrow();
        assertThat(unfrozenStock.isFrozen()).isFalse();
        assertThat(unfrozenStock.getOnHand()).isEqualTo(18);

        // Reservation should now succeed
        var reserveResp = reservationService.reserve(request);
        assertThat(reserveResp).isNotNull();
        assertThat(reserveResp.reservationId()).isNotNull();
    }
}
