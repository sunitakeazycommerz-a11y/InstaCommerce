package com.instacommerce.notification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.notification.config.JacksonConfig;
import com.instacommerce.notification.config.SecurityConfig;
import com.instacommerce.notification.dto.DeviceTokenResponse;
import com.instacommerce.notification.exception.GlobalExceptionHandler;
import com.instacommerce.notification.exception.TraceIdProvider;
import com.instacommerce.notification.security.InternalServiceAuthFilter;
import com.instacommerce.notification.security.JwtAuthenticationFilter;
import com.instacommerce.notification.security.JwtService;
import com.instacommerce.notification.security.RestAccessDeniedHandler;
import com.instacommerce.notification.security.RestAuthenticationEntryPoint;
import com.instacommerce.notification.service.DeviceTokenService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = DeviceTokenController.class)
@Import({
    SecurityConfig.class,
    InternalServiceAuthFilter.class,
    JwtAuthenticationFilter.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    TraceIdProvider.class,
    JacksonConfig.class,
    GlobalExceptionHandler.class
})
@TestPropertySource(properties = {
    "internal.service.token=test-internal-service-token"
})
class DeviceTokenControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private DeviceTokenService deviceTokenService;

    @SuppressWarnings("unchecked")
    private void stubJwt(String token, String subject, String role) {
        Jws<Claims> jws = Mockito.mock(Jws.class);
        Claims claims = Mockito.mock(Claims.class);
        when(jws.getPayload()).thenReturn(claims);
        when(claims.getSubject()).thenReturn(subject);
        Collection<? extends GrantedAuthority> authorities =
            List.of(new SimpleGrantedAuthority("ROLE_" + role));
        when(jwtService.validateAccessToken(token)).thenReturn(jws);
        when(jwtService.extractAuthorities(claims)).thenReturn((Collection) authorities);
    }

    @Test
    @DisplayName("POST /notifications/devices returns 401 when unauthenticated")
    void registerAnonymousReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/notifications/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"token":"fcm-token-123","platform":"ANDROID"}
                    """))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /notifications/devices returns 401 on invalid JWT")
    void registerInvalidJwtReturnsUnauthorized() throws Exception {
        when(jwtService.validateAccessToken("bad-token")).thenThrow(new JwtException("invalid"));

        mockMvc.perform(post("/notifications/devices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer bad-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"token":"fcm-token-123","platform":"ANDROID"}
                    """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("POST /notifications/devices allows authenticated user to register own device token")
    void registerAuthenticatedUserReturnsCreated() throws Exception {
        UUID userId = UUID.randomUUID();
        stubJwt("user-jwt", userId.toString(), "USER");
        DeviceTokenResponse response = new DeviceTokenResponse(
            UUID.randomUUID(), userId, "fcm-token-123", "ANDROID", true, Instant.now(), Instant.now()
        );
        when(deviceTokenService.register(any(), any())).thenReturn(response);

        mockMvc.perform(post("/notifications/devices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer user-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"token":"fcm-token-123","platform":"ANDROID"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").value("fcm-token-123"))
            .andExpect(jsonPath("$.userId").value(userId.toString()));

        verify(deviceTokenService).register(any(), any());
    }

    @Test
    @DisplayName("POST /notifications/devices returns 403 when BOLA check fails")
    void registerAnotherUsersTokenReturnsForbidden() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        stubJwt("user-jwt", actorId.toString(), "USER");
        when(deviceTokenService.register(any(), any()))
            .thenThrow(new AccessDeniedException("Cannot register device token for another user"));

        mockMvc.perform(post("/notifications/devices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer user-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":"%s","token":"fcm-token-123","platform":"IOS"}
                    """.formatted(otherId)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("DELETE /notifications/devices/{token} allows owner and forbids non-owner")
    void deactivateChecksOwnership() throws Exception {
        UUID actorId = UUID.randomUUID();
        stubJwt("user-jwt", actorId.toString(), "USER");
        doNothing().when(deviceTokenService).deactivate(eq("my-token"), any());
        doThrow(new AccessDeniedException("Cannot deactivate another user's device token"))
            .when(deviceTokenService).deactivate(eq("other-token"), any());

        mockMvc.perform(delete("/notifications/devices/my-token")
                .header(HttpHeaders.AUTHORIZATION, "Bearer user-jwt"))
            .andExpect(status().isNoContent());

        mockMvc.perform(delete("/notifications/devices/other-token")
                .header(HttpHeaders.AUTHORIZATION, "Bearer user-jwt"))
            .andExpect(status().isForbidden());
    }
}
