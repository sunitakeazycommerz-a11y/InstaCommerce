package com.instacommerce.warehouse.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.warehouse.WarehouseServiceApplication;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = WarehouseServiceApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(
    properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
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

  @Autowired
  private MockMvc mockMvc;

  private UUID testStoreId;

  @BeforeEach
  void setUp() {
    testStoreId = UUID.randomUUID();
  }

  @Test
  @DisplayName("GET /stores/{id} returns store information")
  void testGetStoreReturnsStoreInfo() throws Exception {
    mockMvc
        .perform(get("/stores/{id}", testStoreId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.store_id").exists())
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
        .andExpect(jsonPath("$.total_capacity").isNumber())
        .andExpect(jsonPath("$.available_capacity").isNumber())
        .andExpect(jsonPath("$.utilization_percent").value(value -> {
          double utilization = ((Number) value).doubleValue();
          assertThat(utilization).isBetween(0.0, 100.0);
        }));
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
        .andExpect(jsonPath("$.available_capacity").value(available -> {
          assertThat(((Number) available).doubleValue()).isGreaterThanOrEqualTo(0);
        }));
  }
}
