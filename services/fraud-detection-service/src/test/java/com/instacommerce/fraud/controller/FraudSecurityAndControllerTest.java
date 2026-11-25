package com.instacommerce.fraud.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.fraud.config.FraudProperties;
import com.instacommerce.fraud.config.JacksonConfig;
import com.instacommerce.fraud.config.SecurityConfig;
import com.instacommerce.fraud.domain.model.AllowlistedEntity;
import com.instacommerce.fraud.domain.model.BlockedEntity;
import com.instacommerce.fraud.dto.request.BlockRequest;
import com.instacommerce.fraud.dto.request.FraudReportRequest;
import com.instacommerce.fraud.dto.response.FraudCheckResponse;
import com.instacommerce.fraud.exception.GlobalExceptionHandler;
import com.instacommerce.fraud.exception.TraceIdProvider;
import com.instacommerce.fraud.repository.FraudRuleRepository;
import com.instacommerce.fraud.security.JwtAuthenticationFilter;
import com.instacommerce.fraud.security.JwtService;
import com.instacommerce.fraud.security.RestAccessDeniedHandler;
import com.instacommerce.fraud.security.RestAuthenticationEntryPoint;
import com.instacommerce.fraud.service.AllowlistService;
import com.instacommerce.fraud.service.BlocklistService;
import com.instacommerce.fraud.service.FraudScoringService;
import com.instacommerce.fraud.service.RuleConditionValidator;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {FraudController.class, AdminFraudController.class})
@EnableConfigurationProperties(FraudProperties.class)
@Import({
    SecurityConfig.class,
    JacksonConfig.class,
    JwtAuthenticationFilter.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    GlobalExceptionHandler.class,
    TraceIdProvider.class
})
@TestPropertySource(properties = {
    "internal.service.token=test-fraud-internal-secret",
    "fraud.jwt.public-key=test-public-key",
    "spring.cloud.gcp.secretmanager.enabled=false",
    "spring.cloud.gcp.sql.enabled=false"
})
@DisplayName("Fraud Security & BOLA Controller Slice Tests")
class FraudSecurityAndControllerTest {

    private static final String INTERNAL_TOKEN = "test-fraud-internal-secret";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FraudScoringService fraudScoringService;

    @MockitoBean
    private FraudRuleRepository fraudRuleRepository;

    @MockitoBean
    private BlocklistService blocklistService;

    @MockitoBean
    private AllowlistService allowlistService;

    @MockitoBean
    private RuleConditionValidator ruleConditionValidator;

    @MockitoBean
    private JwtService jwtService;

    @Test
    @DisplayName("POST /fraud/score rejects CUSTOMER JWT with 403 and accepts INTERNAL_SERVICE or ADMIN")
    void fraudScoreEnforcesInternalServiceOrAdminRole() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String body = """
                {
                  "userId": "%s",
                  "orderId": "%s",
                  "totalCents": 2500,
                  "deviceFingerprint": "dev-1",
                  "ipAddress": "127.0.0.1",
                  "itemCount": 1,
                  "paymentMethodType": "CARD",
                  "isNewUser": false
                }
                """.formatted(userId, orderId);

        mockMvc.perform(post("/fraud/score")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        mockBearerJwt("customer-jwt", userId.toString(), List.of("ROLE_CUSTOMER"));
        mockMvc.perform(post("/fraud/score")
                        .header("Authorization", "Bearer customer-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        when(fraudScoringService.scoreTransaction(any()))
                .thenReturn(new FraudCheckResponse(10, "LOW", "ALLOW", List.of(), null));

        mockMvc.perform(post("/fraud/score")
                        .header("X-Internal-Service", "checkout-orchestrator-service")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("ALLOW"));
    }

    @Test
    @DisplayName("POST /fraud/report binds userId to authenticated subject for non-privileged callers")
    void fraudReportBindsAuthenticatedUserId() throws Exception {
        UUID authenticatedUser = UUID.randomUUID();
        UUID spoofedTargetUser = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        mockBearerJwt("cust-token", authenticatedUser.toString(), List.of("ROLE_CUSTOMER"));
        when(fraudScoringService.reportFraud(any()))
                .thenReturn(Map.of("status", "reported", "signalId", UUID.randomUUID().toString()));

        String body = """
                {
                  "userId": "%s",
                  "orderId": "%s",
                  "reason": "suspicious charge"
                }
                """.formatted(spoofedTargetUser, orderId);

        mockMvc.perform(post("/fraud/report")
                        .header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("reported"));

        ArgumentCaptor<FraudReportRequest> captor = ArgumentCaptor.forClass(FraudReportRequest.class);
        verify(fraudScoringService).reportFraud(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(authenticatedUser);
        assertThat(captor.getValue().orderId()).isEqualTo(orderId);
    }

    @Test
    @DisplayName("/admin/fraud/** allows admin-gateway-service and ADMIN while denying order-service")
    void adminFraudRoutesEnforceServiceAndRoleAllowlist() throws Exception {
        when(fraudRuleRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/admin/fraud/rules")
                        .header("X-Internal-Service", "order-service")
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin/fraud/rules")
                        .header("X-Internal-Service", "admin-gateway-service")
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk());

        AllowlistedEntity entity = new AllowlistedEntity();
        entity.setId(UUID.randomUUID());
        entity.setEntityType("USER");
        entity.setEntityValue("user-vip-1");
        entity.setAllowedBy("admin-1");
        when(allowlistService.listActive()).thenReturn(List.of(entity));
        when(allowlistService.allowEntity(any(BlockRequest.class), eq("admin-1"))).thenReturn(entity);

        mockBearerJwt("admin-token", "admin-1", List.of("ROLE_ADMIN"));
        mockMvc.perform(get("/admin/fraud/allowlist")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entityValue").value("user-vip-1"));

        mockMvc.perform(post("/admin/fraud/allowlist")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "entityType": "USER",
                                  "entityValue": "user-vip-1",
                                  "reason": "verified merchant"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allowedBy").value("admin-1"));

        BlockedEntity blocked = new BlockedEntity();
        blocked.setId(UUID.randomUUID());
        blocked.setEntityType("IP");
        blocked.setEntityValue("203.0.113.9");
        when(blocklistService.block(eq("IP"), eq("203.0.113.9"), eq("abuse"), eq(null), eq("admin-1")))
                .thenReturn(blocked);
        mockMvc.perform(post("/admin/fraud/blocklist")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "entityType": "IP",
                                  "entityValue": "203.0.113.9",
                                  "reason": "abuse"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/admin/fraud/allowlist/{id}", entity.getId())
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNoContent());
        verify(allowlistService).revokeAllowlist(entity.getId());
    }

    @SuppressWarnings("unchecked")
    private void mockBearerJwt(String token, String subject, List<String> authorities) {
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(claims.getSubject()).thenReturn(subject);
        Jws<Claims> jws = org.mockito.Mockito.mock(Jws.class);
        when(jws.getPayload()).thenReturn(claims);
        when(jwtService.validateAccessToken(token)).thenReturn(jws);
        doReturn(authorities.stream().map(SimpleGrantedAuthority::new).toList())
                .when(jwtService).extractAuthorities(claims);
    }
}
