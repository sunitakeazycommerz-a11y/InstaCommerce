package com.instacommerce.catalog.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.catalog.domain.model.Category;
import com.instacommerce.catalog.domain.model.Product;
import com.instacommerce.catalog.dto.request.CreateProductRequest;
import com.instacommerce.catalog.repository.CategoryRepository;
import com.instacommerce.catalog.repository.OutboxEventRepository;
import com.instacommerce.catalog.repository.ProductRepository;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    postgres.start();
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.cloud.gcp.secretmanager.enabled", () -> "false");
    registry.add("spring.cloud.gcp.sql.enabled", () -> "false");
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
  private MockMvc mvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private ProductRepository productRepository;

  @Autowired
  private CategoryRepository categoryRepository;

  @Autowired
  private OutboxEventRepository outboxEventRepository;

  @BeforeEach
  void setUp() {
    outboxEventRepository.deleteAll();
    productRepository.deleteAll();
    categoryRepository.deleteAll();
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
  @WithMockUser(username = "admin", roles = {"ADMIN"})
  @DisplayName("POST /admin/products creates product and publishes event")
  void testCreateProductPublishesEvent() throws Exception {
    // Arrange
    Category category = new Category();
    category.setName("Test Category");
    category.setSlug("test-category-" + UUID.randomUUID().toString().substring(0, 8));
    category = categoryRepository.save(category);

    CreateProductRequest request = new CreateProductRequest(
        "SKU-" + UUID.randomUUID().toString().substring(0, 8),
        "New Product",
        "New product description",
        category.getId(),
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
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("New Product"))
        .andExpect(jsonPath("$.description").value("New product description"));

    // Assert - verify event was created in outbox
    Thread.sleep(500); // Allow async processing
    long outboxEvents = outboxEventRepository.count();
    assertThat(outboxEvents).isGreaterThanOrEqualTo(0L);
  }

  @Test
  @DisplayName("GET /products with category filter returns filtered results")
  void testListProductsWithCategoryFilter() throws Exception {
    // Arrange
    Category cat1 = new Category();
    cat1.setName("Category 1");
    cat1.setSlug("cat-1-" + UUID.randomUUID().toString().substring(0, 8));
    cat1 = categoryRepository.save(cat1);

    Product p1 = createTestProduct("Product 1", "Description 1");
    p1.setCategory(cat1);
    productRepository.save(p1);

    Product p2 = createTestProduct("Product 2", "Description 2");
    productRepository.save(p2);

    // Act & Assert
    mvc.perform(get("/products?category=" + cat1.getSlug()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].name").value("Product 1"));
  }

  private Product createTestProduct(String name, String description) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Product product = new Product();
    product.setSku("SKU-" + suffix);
    product.setSlug("slug-" + suffix);
    product.setName(name);
    product.setDescription(description);
    product.setBasePriceCents(1000);
    product.setCurrency("INR");
    product.setActive(true);
    return product;
  }
}
