package com.instacommerce.warehouse.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.warehouse.WarehouseServiceApplication;
import com.instacommerce.warehouse.domain.model.Store;
import com.instacommerce.warehouse.domain.model.StoreHours;
import com.instacommerce.warehouse.domain.model.StoreStatus;
import com.instacommerce.warehouse.domain.model.StoreZone;
import com.instacommerce.warehouse.repository.StagingTelemetryRepository;
import com.instacommerce.warehouse.repository.StoreRepository;
import com.instacommerce.warehouse.repository.WarehouseBinRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.springframework.security.test.context.support.WithMockUser;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = WarehouseServiceApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "warehouse-test-user", roles = {"USER"})
@TestPropertySource(
    properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
    }
)
@DisplayName("Warehouse Service Integration Tests")
class WarehouseIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("test_warehouse")
          .withUsername("test")
          .withPassword("test");

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    postgres.start();
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @BeforeAll
  static void runMigrations() {
    postgres.start();
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private StoreRepository storeRepository;

  @Autowired
  private WarehouseBinRepository binRepository;

  @Autowired
  private StagingTelemetryRepository telemetryRepository;

  private UUID testStoreId;

  @BeforeEach
  void setUp() {
    binRepository.deleteAll();
    telemetryRepository.deleteAll();
    storeRepository.deleteAll();

    Store store = new Store();
    store.setName("Test Dark Store");
    store.setAddress("123 Indiranagar 100ft Rd");
    store.setCity("Bangalore");
    store.setState("Karnataka");
    store.setPincode("560038");
    store.setLatitude(new BigDecimal("12.97160000"));
    store.setLongitude(new BigDecimal("77.59460000"));
    store.setStatus(StoreStatus.ACTIVE);
    store.setCapacityOrdersPerHour(100);
    store.setMaxConcurrentOrders(10);
    store.setCurrentPendingOrders(0);

    StoreZone zone = new StoreZone();
    zone.setZoneName("Indiranagar Core");
    zone.setPincode("560038");
    zone.setDeliveryRadiusKm(new BigDecimal("5.00"));
    store.setZones(List.of(zone));

    StoreHours hours = new StoreHours();
    hours.setDayOfWeek(LocalDate.now().getDayOfWeek().getValue() % 7);
    hours.setOpensAt(LocalTime.MIN);
    hours.setClosesAt(LocalTime.MAX);
    hours.setHoliday(false);
    store.setHours(List.of(hours));

    Store saved = storeRepository.save(store);
    testStoreId = saved.getId();
  }

  @Test
  @DisplayName("GET /stores/{id} returns store information")
  void testGetStoreReturnsStoreInfo() throws Exception {
    mockMvc
        .perform(get("/stores/{id}", testStoreId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").exists())
        .andExpect(jsonPath("$.name").exists())
        .andExpect(jsonPath("$.latitude").isNumber())
        .andExpect(jsonPath("$.longitude").isNumber());
  }

  @Test
  @DisplayName("GET /stores/{id}/capacity returns warehouse capacity")
  void testGetStoreCapacityReturnsValidCapacity() throws Exception {
    mockMvc
        .perform(get("/stores/{id}/capacity", testStoreId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxOrders").isNumber())
        .andExpect(jsonPath("$.currentOrders").isNumber())
        .andExpect(jsonPath("$.canAcceptOrders").isBoolean());
  }

  @Test
  @DisplayName("GET /stores/{id}/open returns store open status")
  void testGetStoreOpenStatusReturnsBoolean() throws Exception {
    mockMvc
        .perform(get("/stores/{id}/open", testStoreId))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("GET /stores/by-city returns list of stores for city")
  void testFindStoresByCity() throws Exception {
    mockMvc
        .perform(get("/stores/by-city").param("city", "Bangalore"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
  }

  @Test
  @DisplayName("GET /stores/nearest with valid coordinates returns nearby stores")
  void testFindNearestStoresWithValidCoordinates() throws Exception {
    mockMvc
        .perform(
            get("/stores/nearest")
                .param("lat", "12.9716")
                .param("lng", "77.5946")
                .param("radiusKm", "10.0")
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
  }

  @Test
  @DisplayName("GET /stores/nearest with invalid latitude returns 400 Bad Request")
  void testFindNearestStoresWithInvalidLatitude() throws Exception {
    mockMvc
        .perform(
            get("/stores/nearest")
                .param("lat", "100.0")
                .param("lng", "77.5946")
        )
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("GET /stores/{id}/zones returns list of delivery zones")
  void testGetStoreZonesReturnsValidZones() throws Exception {
    mockMvc
        .perform(get("/stores/{id}/zones", testStoreId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
  }

  @Test
  @DisplayName("Warehouse inventory operations maintain data consistency")
  void testWarehouseInventoryDataConsistency() throws Exception {
    mockMvc
        .perform(get("/stores/{id}/capacity", testStoreId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxOrders").isNumber())
        .andExpect(jsonPath("$.maxOrders").value(org.hamcrest.Matchers.greaterThanOrEqualTo(0)));
  }

  @Test
  @DisplayName("Bin assignment supports unique locations and rejects conflicting SKUs (Issue #580)")
  void testBinLocationAssignmentAndCollisionPrevention() throws Exception {
    var assignReq1 = new com.instacommerce.warehouse.dto.request.BinAssignmentRequest(
        "A1", "R1", "S1", "B1", "SKU-BEV-COLA", 10000, 1500);

    // Initial assignment succeeds
    mockMvc.perform(post("/stores/{id}/bins/assign", testStoreId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(assignReq1)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sku").value("SKU-BEV-COLA"))
        .andExpect(jsonPath("$.occupied").value(true))
        .andExpect(jsonPath("$.currentVolumeCm3").value(10000));

    // Duplicate assignment with different SKU to the same bin location throws 409 BIN_ALREADY_OCCUPIED
    var conflictingReq = new com.instacommerce.warehouse.dto.request.BinAssignmentRequest(
        "A1", "R1", "S1", "B1", "SKU-SNACK-CHIPS", 5000, 200);

    mockMvc.perform(post("/stores/{id}/bins/assign", testStoreId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(conflictingReq)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("BIN_ALREADY_OCCUPIED"));

    // Check bin retrieval
    mockMvc.perform(get("/stores/{id}/bins/by-sku", testStoreId)
            .param("sku", "SKU-BEV-COLA"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].binNumber").value("B1"));
  }

  @Test
  @DisplayName("Cold chain staging temperature telemetry triggers alerts and validates dispatch packaging (Issue #585)")
  void testColdChainStagingTelemetryAndThresholdAlerts() throws Exception {
    // 1. Normal reading (2.5°C) for CHILLED bay
    var normalReading = new com.instacommerce.warehouse.dto.request.StagingTelemetryRequest(
        "SENSOR-BT-01", "BAY-CHILLED-1", "CHILLED", new BigDecimal("2.50"), 95);

    mockMvc.perform(post("/stores/{id}/staging/telemetry", testStoreId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(normalReading)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.breached").value(false))
        .andExpect(jsonPath("$.alertTriggered").value(false));

    // 2. High reading (5.5°C > 4.0°C threshold) for CHILLED bay
    var breachReading1 = new com.instacommerce.warehouse.dto.request.StagingTelemetryRequest(
        "SENSOR-BT-01", "BAY-CHILLED-1", "CHILLED", new BigDecimal("5.50"), 94);

    mockMvc.perform(post("/stores/{id}/staging/telemetry", testStoreId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(breachReading1)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.breached").value(true));

    // 3. Second breach reading confirms sustained breach and triggers alert
    var breachReading2 = new com.instacommerce.warehouse.dto.request.StagingTelemetryRequest(
        "SENSOR-BT-01", "BAY-CHILLED-1", "CHILLED", new BigDecimal("6.00"), 93);

    mockMvc.perform(post("/stores/{id}/staging/telemetry", testStoreId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(breachReading2)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.breached").value(true))
        .andExpect(jsonPath("$.alertTriggered").value(true));

    // 4. Validate cold chain dispatch packaging: valid
    var validPackaging = new com.instacommerce.warehouse.dto.request.DispatchPackagingRequest(
        "ORD-9901", "CHILLED", true, true);
    mockMvc.perform(post("/stores/{id}/staging/validate-dispatch", testStoreId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(validPackaging)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(true));

    // 5. Validate cold chain dispatch packaging: missing cold packs throws 400
    var invalidPackaging = new com.instacommerce.warehouse.dto.request.DispatchPackagingRequest(
        "ORD-9902", "CHILLED", true, false);
    mockMvc.perform(post("/stores/{id}/staging/validate-dispatch", testStoreId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(invalidPackaging)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COLD_CHAIN_PACKAGING_REQUIRED"));
  }

  @Test
  @DisplayName("Store layout pick path orders heavy items first and fragile bakery items last (Issue #589)")
  void testDarkStorePickPathOptimizationTSP() throws Exception {
    var items = List.of(
        new com.instacommerce.warehouse.dto.request.PickItemLocationRequest(
            "SKU-BREAD", "Fresh Sourdough Bread", "A10", "S2", "B1", "FRAGILE", 1),
        new com.instacommerce.warehouse.dto.request.PickItemLocationRequest(
            "SKU-WATER", "Mineral Water 5L Pack", "A1", "S1", "B2", "HEAVY", 2),
        new com.instacommerce.warehouse.dto.request.PickItemLocationRequest(
            "SKU-CEREAL", "Corn Flakes Ambient", "A4", "S3", "B5", "AMBIENT", 1)
    );

    var pathReq = new com.instacommerce.warehouse.dto.request.OptimizePathRequest(
        "ENTRANCE", "PACKING", items);

    mockMvc.perform(post("/stores/{id}/layout/optimize-path", testStoreId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(pathReq)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalDistanceMeters").isNumber())
        .andExpect(jsonPath("$.estimatedPickSeconds").isNumber())
        .andExpect(jsonPath("$.pickSteps").isArray())
        // Step 1 must be heavy mineral water
        .andExpect(jsonPath("$.pickSteps[0].sku").value("SKU-WATER"))
        .andExpect(jsonPath("$.pickSteps[0].category").value("HEAVY"))
        // Step 2 must be ambient cereal
        .andExpect(jsonPath("$.pickSteps[1].sku").value("SKU-CEREAL"))
        // Step 3 must be fragile bread
        .andExpect(jsonPath("$.pickSteps[2].sku").value("SKU-BREAD"))
        .andExpect(jsonPath("$.pickSteps[2].category").value("FRAGILE"));
  }

  @Test
  @DisplayName("Dark store capacity throttles at 90% and pauses at 100% capacity (Issue #592)")
  void testDarkStoreCapacityOverloadThrottlingAndPause() throws Exception {
    // testStoreId configured with maxConcurrentOrders = 10 in setUp()
    // Acquire 8 order slots (80% capacity -> NORMAL)
    for (int i = 0; i < 8; i++) {
      mockMvc.perform(post("/stores/{id}/capacity/acquire", testStoreId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$").value(true));
    }

    mockMvc.perform(get("/stores/{id}/capacity", testStoreId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentPendingOrders").value(8))
        .andExpect(jsonPath("$.throttleStatus").value("NORMAL"))
        .andExpect(jsonPath("$.etaExtensionMinutes").value(0));

    // Acquire 9th slot (90% capacity -> THROTTLED)
    mockMvc.perform(post("/stores/{id}/capacity/acquire", testStoreId))
        .andExpect(status().isOk());

    mockMvc.perform(get("/stores/{id}/capacity", testStoreId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentPendingOrders").value(9))
        .andExpect(jsonPath("$.throttleStatus").value("THROTTLED"))
        .andExpect(jsonPath("$.etaExtensionMinutes").value(15))
        .andExpect(jsonPath("$.canAcceptOrders").value(true));

    // Acquire 10th slot (100% capacity -> PAUSED)
    mockMvc.perform(post("/stores/{id}/capacity/acquire", testStoreId))
        .andExpect(status().isOk());

    mockMvc.perform(get("/stores/{id}/capacity", testStoreId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentPendingOrders").value(10))
        .andExpect(jsonPath("$.throttleStatus").value("PAUSED"))
        .andExpect(jsonPath("$.canAcceptOrders").value(false));

    // 11th acquire attempt throws 429 CAPACITY_EXCEEDED
    mockMvc.perform(post("/stores/{id}/capacity/acquire", testStoreId))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("CAPACITY_EXCEEDED"));

    // Release 1 order slot
    mockMvc.perform(post("/stores/{id}/capacity/release", testStoreId))
        .andExpect(status().isOk());

    mockMvc.perform(get("/stores/{id}/capacity", testStoreId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentPendingOrders").value(9))
        .andExpect(jsonPath("$.canAcceptOrders").value(true));
  }
}
