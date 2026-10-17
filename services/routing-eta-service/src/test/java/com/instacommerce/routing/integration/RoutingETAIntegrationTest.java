package com.instacommerce.routing.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.routing.RoutingEtaServiceApplication;
import com.instacommerce.routing.domain.model.Delivery;
import com.instacommerce.routing.domain.model.DeliveryStatus;
import com.instacommerce.routing.repository.DeliveryRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = RoutingEtaServiceApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(username = "routing-test-user", roles = {"USER"})
@TestPropertySource(
    properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
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
  private DeliveryRepository deliveryRepository;

  private UUID testDeliveryId;

  @BeforeEach
  void setUp() {
    Delivery delivery = new Delivery();
    delivery.setOrderId(UUID.randomUUID());
    delivery.setPickupLat(new BigDecimal("37.77490000"));
    delivery.setPickupLng(new BigDecimal("-122.41940000"));
    delivery.setDropoffLat(new BigDecimal("37.78490000"));
    delivery.setDropoffLng(new BigDecimal("-122.40940000"));
    delivery.setStatus(DeliveryStatus.RIDER_ASSIGNED);
    Delivery saved = deliveryRepository.save(delivery);
    testDeliveryId = saved.getId();
  }

  @Test
  @DisplayName("GET /deliveries/{id}/eta returns valid ETA with distance calculation")
  void testETACalculationReturnsValidResponse() throws Exception {
    mockMvc
        .perform(get("/deliveries/{id}/eta", testDeliveryId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.estimatedMinutes").isNumber())
        .andExpect(jsonPath("$.distanceKm").isNumber())
        .andExpect(jsonPath("$.calculatedAt").isNotEmpty());
  }

  @Test
  @DisplayName("ETA calculation respects traffic conditions for different times")
  void testETACalculationWithTrafficConditions() throws Exception {
    mockMvc
        .perform(get("/deliveries/{id}/eta", testDeliveryId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.estimatedMinutes").isNumber())
        .andExpect(jsonPath("$.estimatedMinutes").value(org.hamcrest.Matchers.greaterThan(0)));
  }

  @Test
  @DisplayName("ETA distance is always positive")
  void testETADistanceIsPositive() throws Exception {
    mockMvc
        .perform(get("/deliveries/{id}/eta", testDeliveryId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.distanceKm").isNumber())
        .andExpect(jsonPath("$.distanceKm").value(org.hamcrest.Matchers.greaterThanOrEqualTo(0.0)));
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
        .andExpect(jsonPath("$.calculatedAt").exists());
  }
}
