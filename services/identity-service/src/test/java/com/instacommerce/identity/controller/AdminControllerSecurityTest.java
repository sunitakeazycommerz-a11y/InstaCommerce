package com.instacommerce.identity.controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.instacommerce.identity.config.IdentityProperties;
import com.instacommerce.identity.config.InternalServiceProperties;
import com.instacommerce.identity.config.JacksonConfig;
import com.instacommerce.identity.dto.response.NotificationPreferenceResponse;
import com.instacommerce.identity.dto.response.UserResponse;
import com.instacommerce.identity.exception.GlobalExceptionHandler;
import com.instacommerce.identity.exception.TraceIdProvider;
import com.instacommerce.identity.security.InternalServiceAuthFilter;
import com.instacommerce.identity.security.JwtAuthenticationFilter;
import com.instacommerce.identity.security.JwtService;
import com.instacommerce.identity.security.RestAccessDeniedHandler;
import com.instacommerce.identity.security.RestAuthenticationEntryPoint;
import com.instacommerce.identity.security.SecurityConfig;
import com.instacommerce.identity.service.NotificationPreferenceService;
import com.instacommerce.identity.service.UserService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AdminController.class)
@EnableConfigurationProperties({IdentityProperties.class, InternalServiceProperties.class})
@Import({
    SecurityConfig.class,
    JacksonConfig.class,
    InternalServiceAuthFilter.class,
    JwtAuthenticationFilter.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    GlobalExceptionHandler.class,
    TraceIdProvider.class
})
@TestPropertySource(properties = {
    "internal.service.token=test-internal-secret",
    "identity.jwt.public-key=test-public-key",
    "identity.jwt.private-key=test-private-key"
})
@DisplayName("AdminController Security Slice Tests")
class AdminControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private NotificationPreferenceService notificationPreferenceService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    @DisplayName("Internal service token can access user profile and notification preferences via all path aliases")
    void internalServiceCanReadUsersAndPreferences() throws Exception {
        UUID userId = UUID.randomUUID();
        when(userService.getUserById(userId)).thenReturn(new UserResponse(
            userId, "user@example.com", "Asha", "Rao", "+911234567890",
            List.of("CUSTOMER"), "ACTIVE", Instant.now()));
        when(notificationPreferenceService.getPreferences(userId))
            .thenReturn(new NotificationPreferenceResponse(userId, false, true, false, false));

        mockMvc.perform(get("/admin/users/{id}", userId)
                .header("X-Internal-Service", "notification-service")
                .header("X-Internal-Token", "test-internal-secret"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("user@example.com"));

        mockMvc.perform(get("/users/{id}", userId)
                .header("X-Internal-Service", "mobile-bff-service")
                .header("X-Internal-Token", "test-internal-secret"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(userId.toString()));

        mockMvc.perform(get("/api/v1/users/{id}", userId)
                .header("X-Internal-Service", "mobile-bff-service")
                .header("X-Internal-Token", "test-internal-secret"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(userId.toString()));

        mockMvc.perform(get("/users/{id}/notification-preferences", userId)
                .header("X-Internal-Service", "notification-service")
                .header("X-Internal-Token", "test-internal-secret"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.smsOptOut").value(true));

        mockMvc.perform(get("/api/v1/users/{id}/notification-preferences", userId)
                .header("X-Internal-Service", "notification-service")
                .header("X-Internal-Token", "test-internal-secret"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(userId.toString()));
    }

    @Test
    @DisplayName("Customer role cannot read arbitrary user profiles via /users/{id} or /admin/users/{id}")
    void customerCannotReadOtherUsers() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(get("/users/{id}", userId)
                .with(user("customer").roles("CUSTOMER")))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin/users/{id}", userId)
                .with(user("customer").roles("CUSTOMER")))
            .andExpect(status().isForbidden());
    }
}
