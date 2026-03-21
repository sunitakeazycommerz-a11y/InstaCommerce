package com.instacommerce.admingateway.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.admingateway.config.AdminGatewayTestConfig;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(AdminGatewayTestConfig.class)
@TestPropertySource(properties = {
    "admin-gateway.jwt.issuer=instacommerce-identity",
    "admin-gateway.jwt.aud=instacommerce-admin",
    "admin-gateway.jwt.clock-skew-seconds=300"
})
@DisplayName("Admin Gateway Integration Tests")
class AdminGatewayIntegrationTest {

  @Autowired
  private MockMvc mvc;

  @Autowired
  private ObjectMapper objectMapper;

  private PrivateKey privateKey;
  private String publicKeyPem;

  @BeforeEach
  void setUp() throws Exception {
    // Load test RSA keys from classpath resources
    String privateKeyPem = new String(Files.readAllBytes(
        Paths.get("src/test/resources/test-private-key.pem")));
    publicKeyPem = new String(Files.readAllBytes(
        Paths.get("src/test/resources/test-public-key.pem")));

    privateKey = parsePrivateKey(privateKeyPem);
  }

  @Test
  @DisplayName("GET /admin/v1/dashboard returns aggregated metrics with valid JWT")
  void testDashboardWithValidToken() throws Exception {
    String validToken = generateValidToken("admin-user", "instacommerce-admin",
        Instant.now().plus(1, ChronoUnit.HOURS));

    mvc.perform(get("/admin/v1/dashboard")
        .header("Authorization", "Bearer " + validToken)
        .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"))
        .andExpect(jsonPath("$.orderVolume.today").value(1524))
        .andExpect(jsonPath("$.paymentVolume.today").value(85420.50))
        .andExpect(jsonPath("$.fulfillmentRate.pending").value(234));
  }

  @Test
  @DisplayName("POST /admin/v1/flags/{id}/override applies flag override")
  void testFlagOverrideWithValidToken() throws Exception {
    String validToken = generateValidToken("admin-user", "instacommerce-admin",
        Instant.now().plus(1, ChronoUnit.HOURS));

    String requestBody = objectMapper.writeValueAsString(
        new java.util.HashMap<>(java.util.Map.of(
            "value", true,
            "ttlSeconds", 600
        ))
    );

    mvc.perform(post("/admin/v1/flags/new-checkout-flow/override")
        .header("Authorization", "Bearer " + validToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.flagId").value("new-checkout-flow"))
        .andExpect(jsonPath("$.override").value(true))
        .andExpect(jsonPath("$.status").value("applied"));
  }

  @Test
  @DisplayName("GET /admin/v1/dashboard denied without valid JWT")
  void testDashboardWithoutToken() throws Exception {
    mvc.perform(get("/admin/v1/dashboard")
        .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
  }

  @Test
  @DisplayName("GET /admin/v1/dashboard denied with wrong audience JWT")
  void testDashboardWithWrongAudienceToken() throws Exception {
    String wrongAudToken = generateValidToken("admin-user", "wrong-audience",
        Instant.now().plus(1, ChronoUnit.HOURS));

    mvc.perform(get("/admin/v1/dashboard")
        .header("Authorization", "Bearer " + wrongAudToken)
        .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
  }

  @Test
  @DisplayName("GET /admin/v1/dashboard denied with expired JWT")
  void testDashboardWithExpiredToken() throws Exception {
    String expiredToken = generateValidToken("admin-user", "instacommerce-admin",
        Instant.now().minus(1, ChronoUnit.HOURS));

    mvc.perform(get("/admin/v1/dashboard")
        .header("Authorization", "Bearer " + expiredToken)
        .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
  }

  @Test
  @DisplayName("GET /admin/v1/dashboard denied with invalid signature")
  void testDashboardWithInvalidSignature() throws Exception {
    String invalidToken = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJhZG1pbi11c2VyIiwiYXVkIjoiaW5zdGFjb21tZXJjZS1hZG1pbiIsImV4cCI6OTk5OTk5OTk5OX0.invalid";

    mvc.perform(get("/admin/v1/dashboard")
        .header("Authorization", "Bearer " + invalidToken)
        .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
  }

  @Test
  @DisplayName("GET /admin/health accessible without JWT authentication")
  void testHealthEndpointWithoutToken() throws Exception {
    mvc.perform(get("/admin/health"))
        .andExpect(status().isOk());
  }

  private String generateValidToken(String subject, String audience, Instant expiresAt) {
    return Jwts.builder()
        .subject(subject)
        .issuer("instacommerce-identity")
        .audience()
        .add(audience)
        .and()
        .expiration(Date.from(expiresAt))
        .signWith(privateKey, SignatureAlgorithm.RS256)
        .compact();
  }

  private PrivateKey parsePrivateKey(String keyPem) throws Exception {
    String normalized = keyPem
        .replaceAll("-----BEGIN PRIVATE KEY-----", "")
        .replaceAll("-----END PRIVATE KEY-----", "")
        .replaceAll("\\s", "");
    byte[] decoded = Base64.getDecoder().decode(normalized);
    PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
    return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
  }
}
