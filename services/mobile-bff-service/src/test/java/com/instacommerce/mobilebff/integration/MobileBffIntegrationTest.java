package com.instacommerce.mobilebff.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.reactive.server.WebTestClient.bindToApplicationContext;

import com.instacommerce.mobilebff.MobileBffServiceApplication;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    classes = MobileBffServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@DisplayName("Mobile BFF Service Integration Tests")
class MobileBffIntegrationTest {

  private static KeyPair testKeyPair;
  private static String testPublicKeyBase64;

  @BeforeAll
  static void generateKeys() throws Exception {
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
    kpg.initialize(2048);
    testKeyPair = kpg.generateKeyPair();
    testPublicKeyBase64 = Base64.getEncoder().encodeToString(testKeyPair.getPublic().getEncoded());
  }

  @DynamicPropertySource
  static void registerJwtProperties(DynamicPropertyRegistry registry) {
    registry.add("mobile-bff.jwt.issuer", () -> "instacommerce-identity");
    registry.add("mobile-bff.jwt.public-key", () -> testPublicKeyBase64);
  }

  @Autowired
  private ApplicationContext applicationContext;

  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    webTestClient = bindToApplicationContext(applicationContext).build();
  }

  private String createTestToken(String subject, List<String> roles, Instant expiresAt) {
    return Jwts.builder()
        .subject(subject)
        .claim("roles", roles)
        .issuer("instacommerce-identity")
        .issuedAt(new Date())
        .expiration(Date.from(expiresAt))
        .signWith(testKeyPair.getPrivate(), Jwts.SIG.RS256)
        .compact();
  }

  @Test
  @DisplayName("GET /bff/mobile/v1/home should return ok status without authentication")
  void testHomeEndpointReturnsOk() {
    webTestClient
        .get()
        .uri("/bff/mobile/v1/home")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .consumeWith(
            response -> assertThat(response.getResponseBody()).isNotNull()
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
  @DisplayName("GET /bff/mobile/v1/profile without token should return 401 Unauthorized")
  void testProfileEndpointWithoutTokenReturnsUnauthorized() {
    webTestClient
        .get()
        .uri("/bff/mobile/v1/profile")
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  @DisplayName("GET /bff/mobile/v1/profile with valid token should return 200 OK and user details")
  void testProfileEndpointWithValidTokenReturnsOk() {
    String token = createTestToken("usr_12345", List.of("CUSTOMER"), Instant.now().plus(1, ChronoUnit.HOURS));

    webTestClient
        .get()
        .uri("/bff/mobile/v1/profile")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.userId").isEqualTo("usr_12345")
        .jsonPath("$.status").isEqualTo("authenticated");
  }

  @Test
  @DisplayName("GET /bff/mobile/v1/profile with expired token should return 401 Unauthorized")
  void testProfileEndpointWithExpiredTokenReturnsUnauthorized() {
    String token = createTestToken("usr_12345", List.of("CUSTOMER"), Instant.now().minus(1, ChronoUnit.HOURS));

    webTestClient
        .get()
        .uri("/bff/mobile/v1/profile")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .exchange()
        .expectStatus()
        .isUnauthorized();
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
  @DisplayName("Invalid path with valid auth should return 404 Not Found")
  void testInvalidPathReturns404() {
    String token = createTestToken("usr_12345", List.of("CUSTOMER"), Instant.now().plus(1, ChronoUnit.HOURS));

    webTestClient
        .get()
        .uri("/bff/mobile/v1/invalid")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .exchange()
        .expectStatus()
        .isNotFound();
  }
}
