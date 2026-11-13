package com.instacommerce.audit.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.audit.config.JacksonConfig;
import com.instacommerce.audit.config.ShedLockConfig;
import com.instacommerce.audit.controller.AdminAuditController;
import com.instacommerce.audit.controller.AuditIngestionController;
import com.instacommerce.audit.dto.AuditEventRequest;
import com.instacommerce.audit.dto.AuditEventResponse;
import com.instacommerce.audit.service.AuditExportService;
import com.instacommerce.audit.service.AuditIngestionService;
import com.instacommerce.audit.service.AuditQueryService;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

@WebMvcTest(
        controllers = {AuditIngestionController.class, AdminAuditController.class},
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ShedLockConfig.class),
        properties = {
                "audit.jwt.issuer=instacommerce-identity",
                "internal.service.token=test-internal-service-token"
        })
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        InternalServiceAuthFilter.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        JacksonConfig.class,
        AuditSecurityConfigTest.ActuatorStubConfig.class
})
class AuditSecurityConfigTest {

    private static final String INTERNAL_TOKEN = "test-internal-service-token";
    private static final String VALID_EVENT_JSON = """
            {
              "eventType": "ORDER_CREATED",
              "sourceService": "order-service",
              "action": "CREATE"
            }
            """;
    private static final String VALID_BATCH_JSON = "[" + VALID_EVENT_JSON + "]";

    private static KeyPair rsaKeyPair;
    private static String publicKeyPem;

    @BeforeAll
    static void initKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        rsaKeyPair = generator.generateKeyPair();
        publicKeyPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(rsaKeyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    @DynamicPropertySource
    static void registerJwtPublicKey(DynamicPropertyRegistry registry) {
        registry.add("audit.jwt.public-key", () -> publicKeyPem);
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean
    private AuditIngestionService ingestionService;

    @MockitoBean
    private AuditQueryService queryService;

    @MockitoBean
    private AuditExportService exportService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        when(ingestionService.ingest(any(AuditEventRequest.class)))
                .thenReturn(new AuditEventResponse(
                        UUID.randomUUID(), "ORDER_CREATED", "order-service", null, null,
                        null, null, "CREATE", Map.of(), null, null, null,
                        1L, "hash", "prev", Instant.now()));
        when(queryService.query(any())).thenReturn(Page.empty());
    }

    @Test
    @DisplayName("Rejects unauthenticated POST /audit/events with 401")
    void unauthenticatedIngest_returns401() throws Exception {
        mockMvc.perform(post("/audit/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_EVENT_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Rejects unauthenticated POST /audit/events/batch with 401")
    void unauthenticatedBatchIngest_returns401() throws Exception {
        mockMvc.perform(post("/audit/events/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BATCH_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Rejects invalid internal service token with 401")
    void invalidInternalToken_returns401() throws Exception {
        mockMvc.perform(post("/audit/events")
                        .header("X-Internal-Service", "order-service")
                        .header("X-Internal-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_EVENT_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Forbids plain ROLE_USER JWT on POST /audit/events and /audit/events/batch")
    void roleUserJwt_returns403() throws Exception {
        String userJwt = mintJwt("user-123", List.of("USER"));

        mockMvc.perform(post("/audit/events")
                        .header("Authorization", "Bearer " + userJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_EVENT_JSON))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/audit/events/batch")
                        .header("Authorization", "Bearer " + userJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BATCH_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Allows valid internal service token on POST /audit/events and /audit/events/batch")
    void validInternalServiceToken_returns201() throws Exception {
        mockMvc.perform(post("/audit/events")
                        .header("X-Internal-Service", "admin-gateway-service")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_EVENT_JSON))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/audit/events/batch")
                        .header("X-Internal-Service", "order-service")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BATCH_JSON))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Forbids internal service token from /admin/** routes")
    void internalServiceTokenOnAdminRoute_returns403() throws Exception {
        mockMvc.perform(get("/admin/audit/events")
                        .header("X-Internal-Service", "order-service")
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Allows ROLE_SYSTEM, ROLE_SERVICE, ROLE_INTERNAL_SERVICE, and ROLE_ADMIN on POST /audit/events")
    void allowedRoles_canIngestAuditEvents() throws Exception {
        for (String role : List.of("SYSTEM", "SERVICE", "INTERNAL_SERVICE", "ADMIN")) {
            mockMvc.perform(post("/audit/events")
                            .with(user("caller-" + role).roles(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_EVENT_JSON))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    @DisplayName("Forbids /api/v1/audit/events for ROLE_USER")
    void apiV1Alias_forbidsRoleUser() throws Exception {
        mockMvc.perform(post("/api/v1/audit/events")
                        .with(user("regular-user").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_EVENT_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Keeps /actuator/** and /error routes permitted without auth")
    void actuatorAndErrorRoutes_remainPermitAll() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        MvcResult errorResult = mockMvc.perform(get("/error")).andReturn();
        assertThat(errorResult.getResponse().getStatus()).isNotIn(401, 403);
    }

    @TestConfiguration
    static class ActuatorStubConfig {
        @RestController
        static class ActuatorStubController {
            @GetMapping("/actuator/health")
            Map<String, String> health() {
                return Map.of("status", "UP");
            }
        }
    }

    private static String mintJwt(String subject, List<String> roles) {
        return Jwts.builder()
                .issuer("instacommerce-identity")
                .subject(subject)
                .claim("roles", roles)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(rsaKeyPair.getPrivate())
                .compact();
    }
}
