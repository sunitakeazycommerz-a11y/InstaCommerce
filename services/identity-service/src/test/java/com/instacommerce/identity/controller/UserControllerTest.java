package com.instacommerce.identity.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.identity.dto.request.NotificationPreferenceRequest;
import com.instacommerce.identity.dto.request.UpdateProfileRequest;
import com.instacommerce.identity.dto.response.NotificationPreferenceResponse;
import com.instacommerce.identity.dto.response.UserResponse;
import com.instacommerce.identity.exception.GlobalExceptionHandler;
import com.instacommerce.identity.exception.TraceIdProvider;
import com.instacommerce.identity.service.NotificationPreferenceService;
import com.instacommerce.identity.service.UserDeletionService;
import com.instacommerce.identity.service.UserService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserController Unit Tests")
class UserControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @Mock
    private UserService userService;

    @Mock
    private UserDeletionService userDeletionService;

    @Mock
    private NotificationPreferenceService notificationPreferenceService;

    @BeforeEach
    void setUp() {
        TraceIdProvider traceIdProvider = new TraceIdProvider();
        UserController controller = new UserController(
            userService, userDeletionService, traceIdProvider, notificationPreferenceService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(traceIdProvider))
            .build();
    }

    @Test
    @DisplayName("PATCH /users/me updates profile fields and notification preferences")
    void patchUsersMeUpdatesProfileAndPreferences() throws Exception {
        UUID userId = UUID.randomUUID();
        UserResponse updatedUser = new UserResponse(
            userId, "user@example.com", "Asha", "Rao", "+919876543210",
            List.of("CUSTOMER"), "ACTIVE", Instant.now());
        when(userService.updateProfile(any(UpdateProfileRequest.class), any(), any(), any()))
            .thenReturn(updatedUser);
        when(notificationPreferenceService.updatePreferences(eq(userId), any(NotificationPreferenceRequest.class)))
            .thenReturn(new NotificationPreferenceResponse(userId, true, false, false, true));

        UpdateProfileRequest request = new UpdateProfileRequest(
            "Asha", "Rao", "+919876543210",
            new NotificationPreferenceRequest(true, false, false, true));

        mockMvc.perform(patch("/users/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(userId.toString()))
            .andExpect(jsonPath("$.firstName").value("Asha"))
            .andExpect(jsonPath("$.lastName").value("Rao"))
            .andExpect(jsonPath("$.phone").value("+919876543210"));

        verify(userService).updateProfile(eq(request), any(), any(), any());
        verify(notificationPreferenceService).updatePreferences(eq(userId), eq(request.notificationPreferences()));
    }

    @Test
    @DisplayName("PATCH /api/v1/users/me supports partial profile update without notification preferences")
    void patchApiV1UsersMeUpdatesPartialProfile() throws Exception {
        UUID userId = UUID.randomUUID();
        UserResponse updatedUser = new UserResponse(
            userId, "user@example.com", "Vikram", null, null,
            List.of("CUSTOMER"), "ACTIVE", Instant.now());
        when(userService.updateProfile(any(UpdateProfileRequest.class), any(), any(), any()))
            .thenReturn(updatedUser);

        UpdateProfileRequest request = new UpdateProfileRequest("Vikram", null, null, null);

        mockMvc.perform(patch("/api/v1/users/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.firstName").value("Vikram"));

        verify(userService).updateProfile(eq(request), any(), any(), any());
        verifyNoInteractions(notificationPreferenceService);
    }

    @Test
    @DisplayName("GET /api/v1/users/me returns current authenticated user")
    void getApiV1UsersMeReturnsUser() throws Exception {
        UUID userId = UUID.randomUUID();
        when(userService.getCurrentUser()).thenReturn(new UserResponse(
            userId, "user@example.com", "Asha", "Rao", null,
            List.of("CUSTOMER"), "ACTIVE", Instant.now()));

        mockMvc.perform(get("/api/v1/users/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(userId.toString()));
    }
}
