package com.instacommerce.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instacommerce.inventory.InventoryServiceApplication;
import com.instacommerce.inventory.domain.model.Reservation;
import com.instacommerce.inventory.domain.model.ReservationStatus;
import com.instacommerce.inventory.domain.model.StockItem;
import com.instacommerce.inventory.dto.request.InventoryItemRequest;
import com.instacommerce.inventory.dto.request.ReserveRequest;
import com.instacommerce.inventory.dto.response.ReserveResponse;
import com.instacommerce.inventory.exception.InsufficientStockException;
import com.instacommerce.inventory.repository.ReservationRepository;
import com.instacommerce.inventory.repository.StockItemRepository;
import com.instacommerce.inventory.service.ReservationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link ReservationService} proving stock reservation,
 * confirmation, cancellation, and insufficient-stock rejection against a real
 * PostgreSQL instance with Flyway migrations.
 */
@SpringBootTest(
    classes = InventoryServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ReservationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private StockItemRepository stockItemRepository;

    @Autowired
    private ReservationRepository reservationRepository;

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

    // -- shouldReserveStock -------------------------------------------------------

    @Test
    void shouldReserveStock() {
        UUID productId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        StockItem stock = createStockItem(productId, storeId, 100, 0);

        ReserveRequest request = new ReserveRequest(
            "reserve-key-" + UUID.randomUUID(),
            storeId,
            List.of(new InventoryItemRequest(productId, 10))
        );

        ReserveResponse response = reservationService.reserve(request);

        assertThat(response).isNotNull();
        assertThat(response.reservationId()).isNotNull();
        assertThat(response.expiresAt()).isNotNull();

        // Verify stock reserved quantity increased
        StockItem updated = stockItemRepository.findByProductIdAndStoreId(productId, storeId).orElseThrow();
        assertThat(updated.getReserved()).isEqualTo(10);
        assertThat(updated.getOnHand()).isEqualTo(100); // on_hand unchanged during reservation
    }

    // -- shouldConfirmReservation -------------------------------------------------

    @Test
    void shouldConfirmReservation() {
        UUID productId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        createStockItem(productId, storeId, 100, 0);

        ReserveRequest request = new ReserveRequest(
            "confirm-key-" + UUID.randomUUID(),
            storeId,
            List.of(new InventoryItemRequest(productId, 10))
        );
        ReserveResponse reservation = reservationService.reserve(request);

        reservationService.confirm(reservation.reservationId());

        Reservation confirmed = reservationRepository.findById(reservation.reservationId()).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);

        // After confirmation: on_hand decremented, reserved released
        StockItem updated = stockItemRepository.findByProductIdAndStoreId(productId, storeId).orElseThrow();
        assertThat(updated.getOnHand()).isEqualTo(90);
        assertThat(updated.getReserved()).isEqualTo(0);
    }

    // -- shouldCancelReservation --------------------------------------------------

    @Test
    void shouldCancelReservation() {
        UUID productId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        createStockItem(productId, storeId, 100, 0);

        ReserveRequest request = new ReserveRequest(
            "cancel-key-" + UUID.randomUUID(),
            storeId,
            List.of(new InventoryItemRequest(productId, 10))
        );
        ReserveResponse reservation = reservationService.reserve(request);

        reservationService.cancel(reservation.reservationId());

        Reservation cancelled = reservationRepository.findById(reservation.reservationId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(ReservationStatus.CANCELLED);

        // After cancellation: stock fully restored
        StockItem updated = stockItemRepository.findByProductIdAndStoreId(productId, storeId).orElseThrow();
        assertThat(updated.getOnHand()).isEqualTo(100);
        assertThat(updated.getReserved()).isEqualTo(0);
    }

    // -- shouldRejectReservationWhenInsufficientStock -----------------------------

    @Test
    void shouldRejectReservationWhenInsufficientStock() {
        UUID productId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        createStockItem(productId, storeId, 5, 0);

        ReserveRequest request = new ReserveRequest(
            "insufficient-key-" + UUID.randomUUID(),
            storeId,
            List.of(new InventoryItemRequest(productId, 10))
        );

        assertThatThrownBy(() -> reservationService.reserve(request))
            .isInstanceOf(InsufficientStockException.class);

        // Verify stock unchanged
        StockItem unchanged = stockItemRepository.findByProductIdAndStoreId(productId, storeId).orElseThrow();
        assertThat(unchanged.getOnHand()).isEqualTo(5);
        assertThat(unchanged.getReserved()).isEqualTo(0);
    }

    // -- helpers -----------------------------------------------------------------

    private StockItem createStockItem(UUID productId, UUID storeId, int onHand, int reserved) {
        StockItem stock = new StockItem();
        stock.setProductId(productId);
        stock.setStoreId(storeId);
        stock.setOnHand(onHand);
        stock.setReserved(reserved);
        return stockItemRepository.save(stock);
    }
}
