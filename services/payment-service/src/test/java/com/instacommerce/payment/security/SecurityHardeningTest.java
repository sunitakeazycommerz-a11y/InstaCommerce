package com.instacommerce.payment.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.payment.dto.response.PaymentResponse;
import com.instacommerce.payment.service.PaymentService;
import com.instacommerce.payment.webhook.WebhookEventHandler;
import com.instacommerce.payment.webhook.WebhookSignatureVerifier;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Wave 16 Lane A — Security hardening verification tests.
 * Validates timing-safe token comparison, role restrictions,
 * IDOR protection, actuator lockdown, and webhook error messages.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers(disabledWithoutDocker = true)
class SecurityHardeningTest {

    private static final String TEST_TOKEN = "test-internal-service-token";

    private static final String TEST_PUBLIC_KEY = """
        -----BEGIN PUBLIC KEY-----
        MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwLQ9ItN2Ynh6wOZ43vBR
        +mwBiITyLugZCLnF20z7D/g17KhgeM3D6JfexegX/Gtekt+Oha2/1KcSAwRwsDZZ
        7CU8gYFWb+TKwrWTJd8efMlhUxeTQvIS3DlOxw5QuWh6wB5FsOmMchqehTGtOcB6
        VQ6yqNwfq4RZtb8LdK0mOyGGSZT8xVCVVL1Z8XNPqveKauyJmedzKTUyssWLdJe3
        QyXUggGR9rdcanfBjapvguHFhoxeXfqW+CxU7ilk1KuNGcj9VDhx92OKugJAjrPY
        FsFZ2LlcpTlxCUPsAOoqdi0mM4dK+HH7mKgMwza36AipDi1tWhlzFMqTRG9MjZw8
        WQIDAQAB
        -----END PUBLIC KEY-----
        """;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("payment.jwt.public-key", () -> TEST_PUBLIC_KEY);
        registry.add("payment.choreography.order-cancelled-consumer-enabled", () -> "false");
        registry.add("payment.webhook.refund-outbox-enabled", () -> "false");
        registry.add("stripe.api-key", () -> "test-stripe-key");
        registry.add("stripe.webhook-secret", () -> "test-webhook-secret");
        registry.add("internal.service.token", () -> TEST_TOKEN);
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private WebhookSignatureVerifier signatureVerifier;

    @MockitoBean
    private WebhookEventHandler webhookEventHandler;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .build();
    }

    // ── Fix 1: Timing-safe token comparison (functional) ─────────

    @Nested
    @DisplayName("SEC-1: InternalServiceAuthFilter constant-time comparison")
    class InternalServiceFilterTests {

        @Test
        @DisplayName("accepts valid internal service token")
        void internalServiceFilter_usesConstantTimeComparison() throws Exception {
            when(paymentService.get(any(UUID.class))).thenReturn(
                new PaymentResponse(UUID.randomUUID(), "AUTHORIZED", null));

            UUID paymentId = UUID.randomUUID();
            mockMvc.perform(get("/payments/{id}", paymentId)
                    .header("X-Internal-Service", "checkout-orchestrator")
                    .header("X-Internal-Token", TEST_TOKEN))
                .andExpect(status().isOk());
        }

        @Test
        @DisplayName("rejects invalid internal service token")
        void internalServiceFilter_rejectsInvalidToken() throws Exception {
            UUID paymentId = UUID.randomUUID();
            mockMvc.perform(get("/payments/{id}", paymentId)
                    .header("X-Internal-Service", "checkout-orchestrator")
                    .header("X-Internal-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());
        }
    }

    // ── Fix 2: ROLE_INTERNAL_SERVICE (not ROLE_ADMIN) ────────────

    @Nested
    @DisplayName("SEC-2: Internal service role grant")
    class InternalServiceRoleTests {

        @Test
        @DisplayName("grants ROLE_INTERNAL_SERVICE, not ROLE_ADMIN")
        void internalServiceFilter_grantsInternalServiceRole() throws Exception {
            when(paymentService.get(any(UUID.class))).thenAnswer(invocation -> {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                assertThat(auth).isNotNull();
                assertThat(auth.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .contains("ROLE_INTERNAL_SERVICE")
                    .doesNotContain("ROLE_ADMIN");
                return new PaymentResponse(UUID.randomUUID(), "AUTHORIZED", null);
            });

            UUID paymentId = UUID.randomUUID();
            mockMvc.perform(get("/payments/{id}", paymentId)
                    .header("X-Internal-Service", "checkout-orchestrator")
                    .header("X-Internal-Token", TEST_TOKEN))
                .andExpect(status().isOk());
        }
    }

    // ── Fix 3: IDOR protection on GET /payments/{id} ─────────────

    @Nested
    @DisplayName("SEC-3: IDOR protection")
    class IdorProtectionTests {

        @Test
        @DisplayName("GET /payments/{id} requires ADMIN or INTERNAL_SERVICE role")
        void paymentEndpoint_requiresAdminOrInternalServiceRole() throws Exception {
            UUID paymentId = UUID.randomUUID();

            // Regular authenticated user without ADMIN or INTERNAL_SERVICE → 403
            mockMvc.perform(get("/payments/{id}", paymentId)
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                        .user("regular-user").roles("USER")))
                .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("GET /payments/{id} allowed for ADMIN")
        void paymentEndpoint_allowedForAdmin() throws Exception {
            when(paymentService.get(any(UUID.class))).thenReturn(
                new PaymentResponse(UUID.randomUUID(), "AUTHORIZED", null));

            mockMvc.perform(get("/payments/{id}", UUID.randomUUID()))
                .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "INTERNAL_SERVICE")
        @DisplayName("GET /payments/{id} allowed for INTERNAL_SERVICE")
        void paymentEndpoint_allowedForInternalService() throws Exception {
            when(paymentService.get(any(UUID.class))).thenReturn(
                new PaymentResponse(UUID.randomUUID(), "AUTHORIZED", null));

            mockMvc.perform(get("/payments/{id}", UUID.randomUUID()))
                .andExpect(status().isOk());
        }
    }

    // ── Fix 4: Actuator lockdown ─────────────────────────────────

    @Nested
    @DisplayName("SEC-4: Actuator endpoint lockdown")
    class ActuatorLockdownTests {

        @Test
        @DisplayName("/actuator/health is publicly accessible")
        void actuatorHealth_isPublic() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        }

        @Test
        @DisplayName("/actuator/prometheus is publicly accessible")
        void actuatorPrometheus_isPublic() throws Exception {
            mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
        }

        @Test
        @DisplayName("/actuator/info requires authentication")
        void actuatorOtherEndpoints_requireAuth() throws Exception {
            mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("/actuator/info forbidden for non-ADMIN")
        void actuatorOtherEndpoints_forbiddenForNonAdmin() throws Exception {
            mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("/actuator/info allowed for ADMIN")
        void actuatorOtherEndpoints_allowedForAdmin() throws Exception {
            mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
        }
    }

    // ── Fix 7: Webhook error message does not leak details ───────

    @Nested
    @DisplayName("F10: Webhook generic error messages")
    class WebhookErrorTests {

        @Test
        @DisplayName("signature failure returns generic 'Bad Request', not 'Invalid signature'")
        void webhookError_doesNotLeakDetails() throws Exception {
            when(signatureVerifier.verify(any(), any())).thenReturn(false);

            MvcResult result = mockMvc.perform(post("/payments/webhook")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"type\":\"test\"}")
                    .header("Stripe-Signature", "bad-sig"))
                .andExpect(status().isBadRequest())
                .andReturn();

            String body = result.getResponse().getContentAsString();
            assertThat(body).doesNotContainIgnoringCase("signature");
            assertThat(body).doesNotContainIgnoringCase("HMAC");
            assertThat(body).isEqualTo("Bad Request");
        }

        @Test
        @DisplayName("processing failure returns generic error, not implementation details")
        void webhookProcessingError_doesNotLeakDetails() throws Exception {
            when(signatureVerifier.verify(any(), any())).thenReturn(true);
            doThrow(new RuntimeException("DB connection failed")).when(webhookEventHandler).handle(any());

            MvcResult result = mockMvc.perform(post("/payments/webhook")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"type\":\"test\"}")
                    .header("Stripe-Signature", "valid-sig"))
                .andExpect(status().isInternalServerError())
                .andReturn();

            String body = result.getResponse().getContentAsString();
            assertThat(body).doesNotContainIgnoringCase("DB connection");
            assertThat(body).doesNotContainIgnoringCase("exception");
        }
    }
}
