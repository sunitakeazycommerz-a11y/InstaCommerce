package com.instacommerce.mobilebff.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.reactive.server.WebTestClient.bindToApplicationContext;

import com.instacommerce.mobilebff.MobileBffServiceApplication;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    classes = MobileBffServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Testcontainers
@TestPropertySource(
    properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
    }
)
@DisplayName("Mobile BFF Service Integration Tests")
class MobileBffIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("test_mobile_bff")
          .withUsername("test")
          .withPassword("test");

  @Autowired
  private WebApplicationContext applicationContext;

  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    webTestClient = bindToApplicationContext(applicationContext).build();
  }

  @Test
  @DisplayName("GET /bff/mobile/v1/home should return ok status")
  void testHomeEndpointReturnsOk() {
    webTestClient
        .get()
        .uri("/bff/mobile/v1/home")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .consumeWith(
            response -> {
              assertThat(response.getResponseBody()).isNotNull();
            }
        );
  }

  @Test
  @DisplayName("GET /m/v1/home should return ok status with alternative path")
  void testHomeEndpointAlternativePathReturnsOk() {
    webTestClient
        .get()
        .uri("/m/v1/home")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .json("{\"status\":\"ok\"}");
  }

  @Test
  @DisplayName("Multiple concurrent requests should be handled properly")
  void testConcurrentRequestHandling() {
    for (int i = 0; i < 5; i++) {
      webTestClient
          .get()
          .uri("/bff/mobile/v1/home")
          .exchange()
          .expectStatus()
          .isOk();
    }
  }

  @Test
  @DisplayName("Invalid path should return 404 Not Found")
  void testInvalidPathReturns404() {
    webTestClient
        .get()
        .uri("/bff/mobile/v1/invalid")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  @DisplayName("Request without required headers should still work")
  void testRequestWithoutOptionalHeadersWorks() {
    webTestClient
        .get()
        .uri("/bff/mobile/v1/home")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(Map.class)
        .consumeWith(
            response -> {
              assertThat(response.getResponseBody()).containsEntry("status", "ok");
            }
        );
  }
}
