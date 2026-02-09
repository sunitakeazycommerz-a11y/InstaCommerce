package com.instacommerce.identity.controller;

import com.instacommerce.identity.dto.request.ChangePasswordRequest;
import com.instacommerce.identity.dto.request.NotificationPreferenceRequest;
import com.instacommerce.identity.dto.response.NotificationPreferenceResponse;
import com.instacommerce.identity.dto.response.UserResponse;
import com.instacommerce.identity.exception.TraceIdProvider;
import com.instacommerce.identity.service.NotificationPreferenceService;
import com.instacommerce.identity.service.UserDeletionService;
import com.instacommerce.identity.service.UserService;
import com.instacommerce.identity.util.RequestContextUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {
    private final UserService userService;
    private final UserDeletionService userDeletionService;
    private final TraceIdProvider traceIdProvider;
    private final NotificationPreferenceService notificationPreferenceService;

    public UserController(UserService userService,
                          UserDeletionService userDeletionService,
                          TraceIdProvider traceIdProvider,
                          NotificationPreferenceService notificationPreferenceService) {
        this.userService = userService;
        this.userDeletionService = userDeletionService;
        this.traceIdProvider = traceIdProvider;
        this.notificationPreferenceService = notificationPreferenceService;
    }

    @GetMapping("/me")
    public UserResponse getMe() {
        return userService.getCurrentUser();
    }

    @GetMapping("/me/notification-preferences")
    public NotificationPreferenceResponse getNotificationPreferences() {
        return notificationPreferenceService.getPreferences(userService.getCurrentUserId());
    }

    @PutMapping("/me/notification-preferences")
    public NotificationPreferenceResponse updateNotificationPreferences(
        @RequestBody NotificationPreferenceRequest request) {
        return notificationPreferenceService.updatePreferences(userService.getCurrentUserId(), request);
    }

    @PostMapping("/me/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                               HttpServletRequest httpRequest) {
        String traceId = traceIdProvider.resolveTraceId(httpRequest);
        userService.changePassword(request,
            RequestContextUtil.resolveIp(httpRequest),
            RequestContextUtil.resolveUserAgent(httpRequest),
            traceId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMe(HttpServletRequest request) {
        UUID userId = userService.getCurrentUserId();
        String traceId = traceIdProvider.resolveTraceId(request);
        userDeletionService.initiateErasure(userId,
            RequestContextUtil.resolveIp(request),
            RequestContextUtil.resolveUserAgent(request),
            traceId);
        return ResponseEntity.noContent().build();
    }
}
