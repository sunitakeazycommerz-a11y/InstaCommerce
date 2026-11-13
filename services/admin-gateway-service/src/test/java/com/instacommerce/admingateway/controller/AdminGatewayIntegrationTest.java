package com.instacommerce.admingateway.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.admingateway.config.AdminGatewayTestConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(AdminGatewayTestConfig.class)
@TestPropertySource(properties = {
    "admin-gateway.jwt.issuer=instacommerce-identity",
    "admin-gateway.jwt.aud=instacommerce-admin",
    "admin-gateway.jwt.clock-skew-seconds=300",
    "admin-gateway.dashboard.timeout-ms=1500",
    "admin-gateway.dashboard.per-service-timeout-ms=750"
})
@DisplayName("Admin Gateway Integration Tests")
class AdminGatewayIntegrationTest {

  private static HttpServer mockDownstreamServer;
  private static int downstreamPort;
  private static int unreachablePort;
  private static final List<RecordedRequest> recordedRequests = new CopyOnWriteArrayList<>();
  private static final AtomicInteger overrideStatus = new AtomicInteger(201);
  private static volatile String overrideResponseBody = "";

  @Autowired
  private MockMvc mvc;

  @Autowired
  private ObjectMapper objectMapper;

  private PrivateKey privateKey;

  @BeforeAll
  static void startMockDownstream() throws IOException {
    mockDownstreamServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    downstreamPort = mockDownstreamServer.getAddress().getPort();

    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      unreachablePort = socket.getLocalPort();
    }

    mockDownstreamServer.createContext("/", AdminGatewayIntegrationTest::handleDownstreamRequest);
    mockDownstreamServer.start();
  }

  @AfterAll
  static void stopMockDownstream() {
    if (mockDownstreamServer != null) {
      mockDownstreamServer.stop(0);
    }
  }

  @DynamicPropertySource
  static void registerDownstreamProperties(DynamicPropertyRegistry registry) {
    String baseUrl = "http://127.0.0.1:" + downstreamPort;
    String closedUrl = "http://127.0.0.1:" + unreachablePort;
    registry.add("admin-gateway.upstream.feature-flag-url", () -> baseUrl);
    registry.add("admin-gateway.upstream.reconciliation-url", () -> baseUrl);
    registry.add("audit-trail.base-url", () -> baseUrl);
    registry.add("admin-gateway.dashboard.services[0].name", () -> "config-feature-flag-service");
    registry.add("admin-gateway.dashboard.services[0].base-url", () -> baseUrl);
    registry.add("admin-gateway.dashboard.services[0].health-path", () -> "/actuator/health");
    registry.add("admin-gateway.dashboard.services[0].metrics-path", () -> "/actuator/metrics/http.server.requests");
    registry.add("admin-gateway.dashboard.services[1].name", () -> "reconciliation-engine");
    registry.add("admin-gateway.dashboard.services[1].base-url", () -> baseUrl);
    registry.add("admin-gateway.dashboard.services[1].health-path", () -> "/ready");
    registry.add("admin-gateway.dashboard.services[1].metrics-path", () -> "/metrics");
    registry.add("admin-gateway.dashboard.services[2].name", () -> "order-service");
    registry.add("admin-gateway.dashboard.services[2].base-url", () -> closedUrl);
    registry.add("admin-gateway.dashboard.services[2].health-path", () -> "/actuator/health");
    registry.add("admin-gateway.dashboard.services[2].metrics-path", () -> "/actuator/metrics/http.server.requests");
  }

  private static void handleDownstreamRequest(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    recordedRequests.add(new RecordedRequest(
        exchange.getRequestMethod(),
        path,
        exchange.getRequestHeaders().getFirst("X-Internal-Service"),
        exchange.getRequestHeaders().getFirst("X-Internal-Token"),
        body));

    int status = 200;
    String contentType = "application/json";
    String response = "{}";

    if ("/actuator/health".equals(path)) {
      response = "{\"status\":\"UP\"}";
    } else if ("/actuator/metrics/http.server.requests".equals(path)) {
      response = "{\"name\":\"http.server.requests\",\"measurements\":[{\"statistic\":\"COUNT\",\"value\":25.0}]}";
    } else if ("/ready".equals(path)) {
      response = "{\"status\":\"ready\"}";
    } else if ("/metrics".equals(path)) {
      contentType = "text/plain; version=0.0.4";
      response = "http_requests_total 10\ngo_goroutines 14\n";
    } else if ("/audit/events".equals(path)) {
      status = 201;
      response = "";
    } else if (path.startsWith("/admin/flags/") && path.endsWith("/overrides")) {
      status = overrideStatus.get();
      response = overrideResponseBody;
    } else {
      status = 404;
      response = "{\"code\":\"NOT_FOUND\"}";
    }

    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
    if (!contentType.isEmpty() && bytes.length > 0) {
      exchange.getResponseHeaders().set("Content-Type", contentType);
    }
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
    exchange.close();
  }

  @BeforeEach
  void setUp() throws Exception {
    recordedRequests.clear();
    overrideStatus.set(201);
    overrideResponseBody = "";
    String privateKeyPem = new String(Files.readAllBytes(
        Paths.get("src/test/resources/test-private-key.pem")));
    privateKey = parsePrivateKey(privateKeyPem);
  }

  @Test
  @DisplayName("GET /admin/v1/dashboard aggregates live health and marks unreachable services degraded")
  void testDashboardWithValidToken() throws Exception {
    String validToken = generateValidToken("admin-user", "instacommerce-admin",
        Instant.now().plus(1, ChronoUnit.HOURS));

    mvc.perform(get("/admin/v1/dashboard")
        .header("Authorization", "Bearer " + validToken)
        .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("degraded"))
        .andExpect(jsonPath("$.summary.totalServices").value(3))
        .andExpect(jsonPath("$.summary.healthyServices").value(2))
        .andExpect(jsonPath("$.summary.degradedServices").value(1))
        .andExpect(jsonPath("$.services.config-feature-flag-service.status").value("UP"))
        .andExpect(jsonPath("$.services.reconciliation-engine.status").value("UP"))
        .andExpect(jsonPath("$.services.order-service.status").value("DEGRADED"));
  }

  @Test
  @DisplayName("POST /admin/v1/flags/{id}/override denied for admin without SUPER_ADMIN role")
  void testFlagOverrideDeniedWithoutSuperAdminToken() throws Exception {
    String adminToken = generateValidToken("admin-user", "instacommerce-admin",
        Instant.now().plus(1, ChronoUnit.HOURS));

    String requestBody = objectMapper.writeValueAsString(Map.of(
        "value", "true",
        "ttlSeconds", 600
    ));

    mvc.perform(post("/admin/v1/flags/new-checkout-flow/override")
        .header("Authorization", "Bearer " + adminToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestBody))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  @DisplayName("POST /admin/v1/flags/{id}/override forwards to feature-flag service with internal headers")
  void testFlagOverrideWithSuperAdminToken() throws Exception {
    String superAdminToken = generateValidToken("super-admin", "instacommerce-admin",
        Instant.now().plus(1, ChronoUnit.HOURS),
        List.of("ROLE_ADMIN", "ROLE_SUPER_ADMIN"));

    String requestBody = objectMapper.writeValueAsString(Map.of(
        "userId", "11111111-1111-1111-1111-111111111111",
        "value", "true",
        "ttlSeconds", 600
    ));

    mvc.perform(post("/admin/v1/flags/new-checkout-flow/override")
        .header("Authorization", "Bearer " + superAdminToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.flagId").value("new-checkout-flow"))
        .andExpect(jsonPath("$.status").value("applied"))
        .andExpect(jsonPath("$.httpStatus").value(201));

    RecordedRequest overrideCall = recordedRequests.stream()
        .filter(r -> "/admin/flags/new-checkout-flow/overrides".equals(r.path()))
        .findFirst()
        .orElseThrow();
    assertThat(overrideCall.method()).isEqualTo("POST");
    assertThat(overrideCall.internalService()).isEqualTo("admin-gateway-service");
    assertThat(overrideCall.internalToken()).isEqualTo("test-internal-service-token");

    RecordedRequest auditCall = recordedRequests.stream()
        .filter(r -> "/audit/events".equals(r.path()))
        .findFirst()
        .orElseThrow();
    assertThat(auditCall.internalService()).isEqualTo("admin-gateway-service");
    assertThat(auditCall.internalToken()).isEqualTo("test-internal-service-token");
  }

  @Test
  @DisplayName("POST /admin/v1/flags/{id}/override propagates downstream HTTP error")
  void testFlagOverridePropagatesDownstreamError() throws Exception {
    overrideStatus.set(404);
    overrideResponseBody = "{\"code\":\"FLAG_NOT_FOUND\",\"message\":\"Flag not found: unknown-flag\"}";

    String superAdminToken = generateValidToken("super-admin", "instacommerce-admin",
        Instant.now().plus(1, ChronoUnit.HOURS),
        List.of("ROLE_ADMIN", "ROLE_SUPER_ADMIN"));

    String requestBody = objectMapper.writeValueAsString(Map.of(
        "userId", "11111111-1111-1111-1111-111111111111",
        "value", "true"
    ));

    mvc.perform(post("/admin/v1/flags/unknown-flag/override")
        .header("Authorization", "Bearer " + superAdminToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestBody))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("FLAG_NOT_FOUND"));
  }

  @Test
  @DisplayName("GET /admin/v1/dashboard denied without valid JWT")
  void testDashboardWithoutToken() throws Exception {
    mvc.perform(get("/admin/v1/dashboard")
        .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
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
    return generateValidToken(subject, audience, expiresAt, List.of("ROLE_ADMIN"));
  }

  private String generateValidToken(String subject, String audience, Instant expiresAt, List<String> roles) {
    return Jwts.builder()
        .subject(subject)
        .issuer("instacommerce-identity")
        .claim("roles", roles)
        .audience()
        .add(audience)
        .and()
        .expiration(Date.from(expiresAt))
        .signWith(privateKey)
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

  private record RecordedRequest(
      String method,
      String path,
      String internalService,
      String internalToken,
      String body) {
  }
}
