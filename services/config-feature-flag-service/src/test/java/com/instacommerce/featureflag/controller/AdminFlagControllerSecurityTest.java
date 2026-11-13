package com.instacommerce.featureflag.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.featureflag.config.JacksonConfig;
import com.instacommerce.featureflag.domain.model.FlagOverride;
import com.instacommerce.featureflag.dto.request.AddOverrideRequest;
import com.instacommerce.featureflag.dto.response.FlagEvaluationResponse;
import com.instacommerce.featureflag.exception.TraceIdProvider;
import com.instacommerce.featureflag.security.InternalServiceAuthFilter;
import com.instacommerce.featureflag.security.JwtAuthenticationFilter;
import com.instacommerce.featureflag.security.JwtService;
import com.instacommerce.featureflag.security.RestAccessDeniedHandler;
import com.instacommerce.featureflag.security.RestAuthenticationEntryPoint;
import com.instacommerce.featureflag.security.SecurityConfig;
import com.instacommerce.featureflag.service.BulkEvaluationService;
import com.instacommerce.featureflag.service.FlagEvaluationService;
import com.instacommerce.featureflag.service.FlagManagementService;
import com.instacommerce.featureflag.service.FlagOverrideService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {AdminFlagController.class, FlagEvaluationController.class})
@Import({
    SecurityConfig.class,
    InternalServiceAuthFilter.class,
    JwtAuthenticationFilter.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    TraceIdProvider.class,
    JacksonConfig.class
})
@TestPropertySource(properties = {
    "internal.service.token=test-internal-service-token",
    "feature-flag.cache.redis-enabled=false"
})
class AdminFlagControllerSecurityTest {

    private static final String INTERNAL_TOKEN = "test-internal-service-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private FlagManagementService flagManagementService;

    @MockitoBean
    private FlagOverrideService flagOverrideService;

    @MockitoBean
    private FlagChangeStreamController flagChangeStreamController;

    @MockitoBean
    private FlagEvaluationService flagEvaluationService;

    @MockitoBean
    private BulkEvaluationService bulkEvaluationService;

    @MockitoBean
    private CacheManager cacheManager;

    private String overridePayload() {
        return """
            {"userId":"%s","value":"true","reason":"incident-mitigation"}
            """.formatted(UUID.randomUUID());
    }

    @SuppressWarnings("unchecked")
    private void stubJwt(String token, String subject, String role) {
        Jws<Claims> jws = Mockito.mock(Jws.class);
        Claims claims = Mockito.mock(Claims.class);
        when(jws.getPayload()).thenReturn(claims);
        when(claims.getSubject()).thenReturn(subject);
        when(jwtService.validateAccessToken(token)).thenReturn(jws);
        Mockito.<Collection<? extends GrantedAuthority>>when(
                jwtService.extractAuthorities(claims))
            .thenReturn(List.of(new SimpleGrantedAuthority(role)));
    }

    @Test
    @DisplayName("POST /admin/flags/{key}/overrides accepts valid internal service credentials")
    void addOverride_allowsInternalService() throws Exception {
        when(flagOverrideService.addOverride(eq("checkout_v2"), any(AddOverrideRequest.class), eq("admin-gateway-service")))
            .thenReturn(new FlagOverride());

        mockMvc.perform(post("/admin/flags/checkout_v2/overrides")
                .header("X-Internal-Service", "admin-gateway-service")
                .header("X-Internal-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(overridePayload()))
            .andExpect(status().isCreated());

        verify(flagOverrideService).addOverride(eq("checkout_v2"), any(AddOverrideRequest.class), eq("admin-gateway-service"));
    }

    @Test
    @DisplayName("POST /admin/flags/{key}/overrides accepts ADMIN JWT")
    void addOverride_allowsAdminJwt() throws Exception {
        stubJwt("admin-jwt", "admin-1", "ROLE_ADMIN");
        when(flagOverrideService.addOverride(eq("checkout_v2"), any(AddOverrideRequest.class), eq("admin-1")))
            .thenReturn(new FlagOverride());

        mockMvc.perform(post("/admin/flags/checkout_v2/overrides")
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content(overridePayload()))
            .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST /admin/flags/{key}/overrides returns 403 for plain USER JWT")
    void addOverride_rejectsPlainUserWith403() throws Exception {
        stubJwt("user-jwt", "user-1", "ROLE_USER");

        mockMvc.perform(post("/admin/flags/checkout_v2/overrides")
                .header(HttpHeaders.AUTHORIZATION, "Bearer user-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content(overridePayload()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("POST /admin/flags/{key}/overrides returns 401 for anonymous caller")
    void addOverride_rejectsAnonymousWith401() throws Exception {
        mockMvc.perform(post("/admin/flags/checkout_v2/overrides")
                .contentType(MediaType.APPLICATION_JSON)
                .content(overridePayload()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("POST /admin/flags/{key}/overrides returns 401 for invalid internal token")
    void addOverride_rejectsInvalidInternalTokenWith401() throws Exception {
        mockMvc.perform(post("/admin/flags/checkout_v2/overrides")
                .header("X-Internal-Service", "admin-gateway-service")
                .header("X-Internal-Token", "wrong-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(overridePayload()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("POST /admin/flags/{key}/overrides returns 401 for invalid bearer JWT")
    void addOverride_rejectsInvalidJwtWith401() throws Exception {
        when(jwtService.validateAccessToken("bad-jwt")).thenThrow(new JwtException("bad"));

        mockMvc.perform(post("/admin/flags/checkout_v2/overrides")
                .header(HttpHeaders.AUTHORIZATION, "Bearer bad-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content(overridePayload()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("Public flag evaluation endpoint remains accessible without auth")
    void publicFlagEvaluationRemainsPermitted() throws Exception {
        when(flagEvaluationService.evaluate(eq("checkout_v2"), any(), any()))
            .thenReturn(new FlagEvaluationResponse("checkout_v2", true, FlagEvaluationResponse.SOURCE_DEFAULT));

        mockMvc.perform(get("/flags/checkout_v2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.key").value("checkout_v2"));
    }

    @Test
    @DisplayName("Unlisted routes still require authentication")
    void nonPublicUnknownRouteRequiresAuth() throws Exception {
        mockMvc.perform(get("/internal-only-route"))
            .andExpect(status().isUnauthorized());
    }
}
