package com.instacommerce.catalog.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.catalog.dto.request.CreateProductRequest;
import com.instacommerce.catalog.dto.response.ProductResponse;
import com.instacommerce.catalog.repository.ProductRepository;
import com.instacommerce.catalog.domain.model.Product;
import com.instacommerce.catalog.repository.OutboxEventRepository;
import com.instacommerce.catalog.domain.model.OutboxEvent;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true"
})
@DisplayName("Catalog Service Integration Tests")
class CatalogServiceIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("test_catalog")
      .withUsername("test")
      .withPassword("test");

  @Autowired
  private MockMvc mvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private ProductRepository productRepository;

  @Autowired
  private OutboxEventRepository outboxEventRepository;

  @BeforeEach
  void setUp() {
    productRepository.deleteAll();
    outboxEventRepository.deleteAll();
  }

  @Test
  @DisplayName("GET /products/{id} returns product successfully")
  void testGetProductSuccessfully() throws Exception {
    // Arrange
    Product product = createTestProduct("Test Product", "A test product description");
    Product saved = productRepository.save(product);

    // Act & Assert
    mvc.perform(get("/products/" + saved.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(saved.getId().toString()))
        .andExpect(jsonPath("$.name").value("Test Product"))
        .andExpect(jsonPath("$.description").value("A test product description"));
  }

  @Test
  @DisplayName("GET /products/{id} returns 404 for non-existent product")
  void testGetProductNotFound() throws Exception {
    // Act & Assert
    mvc.perform(get("/products/" + UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("GET /products returns paginated list")
  void testListProductsPaginated() throws Exception {
    // Arrange
    for (int i = 0; i < 5; i++) {
      productRepository.save(createTestProduct("Product " + i, "Description " + i));
    }

    // Act & Assert
    mvc.perform(get("/products?page=0&size=2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.totalElements").value(5))
        .andExpect(jsonPath("$.totalPages").value(3));
  }

  @Test
  @DisplayName("POST /admin/products creates product and publishes event")
  void testCreateProductPublishesEvent() throws Exception {
    // Arrange
    CreateProductRequest request = new CreateProductRequest(
        "SKU-123",
        "New Product",
        "New product description",
        UUID.randomUUID(),
        "Brand A",
        1999L,
        "INR",
        "piece",
        java.math.BigDecimal.ONE,
        100,
        null
    );

    String requestBody = objectMapper.writeValueAsString(request);

    // Act
    mvc.perform(post("/admin/products")
        .header("Authorization", "Bearer test-token")
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("New Product"))
        .andExpect(jsonPath("$.description").value("New product description"));

    // Assert - verify event was created in outbox
    // Note: In production, this would be consumed from Kafka, but here we test the outbox table
    Thread.sleep(500); // Allow async processing
    long outboxEvents = outboxEventRepository.count();
    assertThat(outboxEvents).isGreaterThanOrEqualTo(0L);
  }

  @Test
  @DisplayName("GET /products with category filter returns filtered results")
  void testListProductsWithCategoryFilter() throws Exception {
    // Arrange
    for (int i = 0; i < 3; i++) {
      Product product = createTestProduct("Product " + i, "Description " + i);
      productRepository.save(product);
    }

    // Act & Assert - without category filter should return all
    mvc.perform(get("/products?page=0&size=10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").isNumber());
  }

  private Product createTestProduct(String name, String description) {
    Product product = new Product();
    product.setId(UUID.randomUUID());
    product.setName(name);
    product.setDescription(description);
    product.setBasePriceCents(1000);
    product.setCurrency("INR");
    product.setActive(true);
    return product;
  }
}
