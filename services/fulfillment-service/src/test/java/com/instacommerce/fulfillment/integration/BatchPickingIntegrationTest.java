package com.instacommerce.fulfillment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instacommerce.fulfillment.FulfillmentServiceApplication;
import com.instacommerce.fulfillment.client.WarehouseClient;
import com.instacommerce.fulfillment.consumer.OrderPlacedItem;
import com.instacommerce.fulfillment.consumer.OrderPlacedPayload;
import com.instacommerce.fulfillment.domain.model.Delivery;
import com.instacommerce.fulfillment.domain.model.DeliveryStatus;
import com.instacommerce.fulfillment.domain.model.PickItemStatus;
import com.instacommerce.fulfillment.domain.model.PickTask;
import com.instacommerce.fulfillment.domain.model.PickTaskStatus;
import com.instacommerce.fulfillment.domain.model.Rider;
import com.instacommerce.fulfillment.dto.request.CreateBatchRequest;
import com.instacommerce.fulfillment.dto.request.MarkItemPickedRequest;
import com.instacommerce.fulfillment.dto.response.BatchPickResponse;
import com.instacommerce.fulfillment.dto.response.DeliveryResponse;
import com.instacommerce.fulfillment.exception.DuplicateScanException;
import com.instacommerce.fulfillment.exception.InvalidCompartmentScanException;
import com.instacommerce.fulfillment.exception.InvalidProofOfDeliveryException;
import com.instacommerce.fulfillment.exception.InvalidRiderHandoffException;
import com.instacommerce.fulfillment.repository.DeliveryRepository;
import com.instacommerce.fulfillment.repository.PickTaskRepository;
import com.instacommerce.fulfillment.repository.RiderRepository;
import com.instacommerce.fulfillment.service.BatchPickingService;
import com.instacommerce.fulfillment.service.DeliveryService;
import com.instacommerce.fulfillment.service.PickService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
    classes = FulfillmentServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class BatchPickingIntegrationTest {

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
    private PickService pickService;

    @Autowired
    private BatchPickingService batchPickingService;

    @Autowired
    private DeliveryService deliveryService;

    @Autowired
    private PickTaskRepository pickTaskRepository;

    @Autowired
    private RiderRepository riderRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

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
                pick_batches,
                riders,
                shedlock
            RESTART IDENTITY CASCADE
            """);
    }

    @Test
    @DisplayName("Should create batch pick run for up to 4 orders with color-coded totes and S-curve route")
    void shouldCreateBatchPickRunAndValidateCompartments() {
        String storeId = "store-blr-01";
        UUID order1 = UUID.randomUUID();
        UUID order2 = UUID.randomUUID();

        createOrder(order1, storeId, List.of(
            new OrderPlacedItem(UUID.randomUUID(), "Almond Milk", "MILK-01", 1, 100, 100, "A01", 1, "BIN-01", "BC-01", 1000)
        ));
        createOrder(order2, storeId, List.of(
            new OrderPlacedItem(UUID.randomUUID(), "Wheat Toast", "BREAD-01", 2, 50, 100, "A02", 3, "BIN-02", "BC-02", 500)
        ));

        UUID pickerId = UUID.randomUUID();
        BatchPickResponse batch = batchPickingService.createBatch(
            new CreateBatchRequest(storeId, "CART-RED-01", 4), pickerId);

        assertThat(batch).isNotNull();
        assertThat(batch.totalOrders()).isEqualTo(2);
        assertThat(batch.totalItems()).isEqualTo(2);
        assertThat(batch.totes()).hasSize(2);
        assertThat(batch.totes().get(0).toteColor()).isEqualTo("RED");
        assertThat(batch.totes().get(1).toteColor()).isEqualTo("BLUE");

        // Validate compartment barcode scan
        String validBarcode = batch.totes().get(0).toteBarcode();
        batchPickingService.validateCompartment(batch.batchId(), order1, validBarcode);

        // Reject mismatching compartment barcode scan
        assertThatThrownBy(() -> batchPickingService.validateCompartment(batch.batchId(), order1, "WRONG-TOTE-999"))
            .isInstanceOf(InvalidCompartmentScanException.class)
            .hasMessageContaining("does not match expected compartment");

        // Complete batch
        BatchPickResponse completed = batchPickingService.completeBatch(batch.batchId());
        assertThat(completed.status()).isEqualTo("COMPLETED");

        PickTask task1 = pickTaskRepository.findByOrderId(order1).orElseThrow();
        assertThat(task1.getStatus()).isEqualTo(PickTaskStatus.COMPLETED);
    }

    @Test
    @DisplayName("Should reject duplicate barcode scans occurring within 1000ms debounce threshold")
    void shouldRejectDuplicateBarcodeScansWithinDebounceWindow() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String storeId = "store-blr-01";

        createOrder(orderId, storeId, List.of(
            new OrderPlacedItem(productId, "Avocado", "AVO-01", 2, 200, 400, "A01", 1, "BIN-01", "BARCODE-AVO-1", 150),
            new OrderPlacedItem(UUID.randomUUID(), "Orange", "ORG-01", 1, 100, 100, "A01", 2, "BIN-02", "BARCODE-ORG-1", 200)
        ));

        UUID pickerId = UUID.randomUUID();
        MarkItemPickedRequest request = new MarkItemPickedRequest(
            PickItemStatus.PICKED, 2, null, "First scan", "BARCODE-AVO-1", "SCANNER-HW-01", 300, System.currentTimeMillis()
        );

        // First scan succeeds
        pickService.markItem(orderId, productId, request, pickerId);

        // Immediate duplicate scan within 1000ms fails
        assertThatThrownBy(() -> pickService.markItem(orderId, productId, request, pickerId))
            .isInstanceOf(DuplicateScanException.class)
            .hasMessageContaining("within 1000ms debounce window");
    }

    @Test
    @DisplayName("Should enforce cryptographic rider handoff check with dynamic OTP before out for delivery")
    void shouldEnforceCryptographicRiderHandoffCheck() {
        UUID orderId = UUID.randomUUID();
        String storeId = "store-blr-01";
        createOrder(orderId, storeId, List.of(
            new OrderPlacedItem(UUID.randomUUID(), "Chips", "CHIPS-01", 1, 100, 100)
        ));

        Rider rider = new Rider();
        rider.setName("John Delivery");
        rider.setPhone("+919876543210");
        rider.setStoreId(storeId);
        rider.setAvailable(true);
        Rider savedRider = riderRepository.save(rider);

        DeliveryResponse assigned = deliveryService.assignRider(orderId, savedRider.getId(), 15);
        assertThat(assigned.dispatchOtp()).isNotNull().hasSize(6);

        // Handoff with invalid OTP fails
        assertThatThrownBy(() -> deliveryService.verifyRiderHandoff(orderId, savedRider.getId(), "000000"))
            .isInstanceOf(InvalidRiderHandoffException.class)
            .hasMessageContaining("verification failed");

        // Handoff with correct OTP succeeds and sets status to PICKED_UP
        DeliveryResponse verified = deliveryService.verifyRiderHandoff(orderId, savedRider.getId(), assigned.dispatchOtp());
        assertThat(verified.status()).isEqualTo(DeliveryStatus.PICKED_UP.name());
    }

    @Test
    @DisplayName("Should validate Proof of Delivery WebP compression and face/PII masking")
    void shouldValidateProofOfDeliveryRequirements() {
        UUID orderId = UUID.randomUUID();
        String storeId = "store-blr-01";
        createOrder(orderId, storeId, List.of(
            new OrderPlacedItem(UUID.randomUUID(), "Coffee Beans", "COFFEE-01", 1, 500, 500)
        ));

        Rider rider = new Rider();
        rider.setName("Alice Rider");
        rider.setPhone("+919123456789");
        rider.setStoreId(storeId);
        rider.setAvailable(true);
        Rider savedRider = riderRepository.save(rider);
        deliveryService.assignRider(orderId, savedRider.getId(), 10);

        // Reject non-WebP format
        assertThatThrownBy(() -> deliveryService.recordProofOfDelivery(orderId, "https://storage.googleapis.com/pod/1.jpg", "JPEG", 50000, true))
            .isInstanceOf(InvalidProofOfDeliveryException.class)
            .hasMessageContaining("only WebP format is allowed");

        // Reject oversized photo (> 200KB)
        assertThatThrownBy(() -> deliveryService.recordProofOfDelivery(orderId, "https://storage.googleapis.com/pod/1.webp", "WEBP", 300000, true))
            .isInstanceOf(InvalidProofOfDeliveryException.class)
            .hasMessageContaining("exceeds 200KB limit");

        // Reject unmasked photo
        assertThatThrownBy(() -> deliveryService.recordProofOfDelivery(orderId, "https://storage.googleapis.com/pod/1.webp", "WEBP", 150000, false))
            .isInstanceOf(InvalidProofOfDeliveryException.class)
            .hasMessageContaining("faces and PII masked");

        // Valid upload succeeds
        DeliveryResponse pod = deliveryService.recordProofOfDelivery(orderId, "https://storage.googleapis.com/pod/1.webp", "WEBP", 120000, true);
        assertThat(pod.podPhotoUrl()).isEqualTo("https://storage.googleapis.com/pod/1.webp");

        Delivery saved = deliveryRepository.findByOrderId(orderId).orElseThrow();
        assertThat(saved.getPodMasked()).isTrue();
        assertThat(saved.getPodPhotoFormat()).isEqualTo("WEBP");
    }

    private void createOrder(UUID orderId, String storeId, List<OrderPlacedItem> items) {
        OrderPlacedPayload payload = new OrderPlacedPayload(
            orderId,
            UUID.randomUUID(),
            storeId,
            UUID.randomUUID(),
            items
        );
        pickService.createPickTask(payload);
    }
}
