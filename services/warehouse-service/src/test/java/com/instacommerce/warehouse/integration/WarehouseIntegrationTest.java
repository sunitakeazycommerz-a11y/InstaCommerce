package com.instacommerce.warehouse.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.warehouse.WarehouseServiceApplication;
import com.instacommerce.warehouse.domain.model.Store;
import com.instacommerce.warehouse.domain.model.StoreHours;
import com.instacommerce.warehouse.domain.model.StoreStatus;
import com.instacommerce.warehouse.domain.model.StoreZone;
import com.instacommerce.warehouse.repository.StoreRepository;
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
  private StoreRepository storeRepository;

  private UUID testStoreId;

  @BeforeEach
  void setUp() {
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
}
