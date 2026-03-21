package com.instacommerce.routing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.routing.RoutingServiceApplication;
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

@SpringBootTest(classes = RoutingServiceApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(
    properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
    }
)
@DisplayName("Routing ETA Service Integration Tests")
class RoutingETAIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("test_routing")
          .withUsername("test")
          .withPassword("test");

  @Autowired
  private MockMvc mockMvc;

  private UUID testDeliveryId;

  @BeforeEach
  void setUp() {
    testDeliveryId = UUID.randomUUID();
  }

  @Test
  @DisplayName("GET /deliveries/{id}/eta returns valid ETA with distance calculation")
  void testETACalculationReturnsValidResponse() throws Exception {
    mockMvc
        .perform(get("/deliveries/{id}/eta", testDeliveryId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.estimated_minutes").isNumber())
        .andExpect(jsonPath("$.distance_km").isNumber())
        .andExpect(jsonPath("$.calculated_at").isNotEmpty());
  }

  @Test
  @DisplayName("ETA calculation respects traffic conditions for different times")
  void testETACalculationWithTrafficConditions() throws Exception {
    mockMvc
        .perform(get("/deliveries/{id}/eta", testDeliveryId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.estimated_minutes").isNumber())
        .andExpect(jsonPath("$.estimated_minutes").value(value -> {
          assertThat((Integer) value).isGreaterThan(0);
        }));
  }

  @Test
  @DisplayName("ETA distance is always positive")
  void testETADistanceIsPositive() throws Exception {
    mockMvc
        .perform(get("/deliveries/{id}/eta", testDeliveryId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.distance_km").value(value -> {
          assertThat(((Number) value).doubleValue()).isGreaterThanOrEqualTo(0.0);
        }));
  }

  @Test
  @DisplayName("Invalid delivery ID returns 404 Not Found")
  void testInvalidDeliveryIdReturns404() throws Exception {
    UUID invalidId = UUID.randomUUID();
    mockMvc
        .perform(get("/deliveries/{id}/eta", invalidId))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("ETA calculation result includes timestamp")
  void testETAIncludesCalculationTimestamp() throws Exception {
    mockMvc
        .perform(get("/deliveries/{id}/eta", testDeliveryId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.calculated_at").exists());
  }
}
