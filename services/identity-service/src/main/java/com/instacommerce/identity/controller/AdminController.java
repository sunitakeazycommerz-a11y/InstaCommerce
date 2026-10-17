package com.instacommerce.identity.controller;

import com.instacommerce.identity.dto.response.NotificationPreferenceResponse;
import com.instacommerce.identity.dto.response.UserResponse;
import com.instacommerce.identity.exception.TraceIdProvider;
import com.instacommerce.identity.service.NotificationPreferenceService;
import com.instacommerce.identity.service.UserService;
import com.instacommerce.identity.util.RequestContextUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/users")
public class AdminController {
    private final UserService userService;
    private final NotificationPreferenceService notificationPreferenceService;
    private final TraceIdProvider traceIdProvider;

    public AdminController(UserService userService,
                           NotificationPreferenceService notificationPreferenceService,
                           org.springframework.beans.factory.ObjectProvider<TraceIdProvider> traceIdProvider) {
        this.userService = userService;
        this.notificationPreferenceService = notificationPreferenceService;
        this.traceIdProvider = traceIdProvider != null ? traceIdProvider.getIfAvailable() : null;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Page<UserResponse> listUsers(@PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return userService.listUsers(pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse getUser(@PathVariable UUID id) {
        return userService.getUserById(id);
    }

    @GetMapping("/{id}/notification-preferences")
    @PreAuthorize("hasRole('ADMIN')")
    public NotificationPreferenceResponse getNotificationPreferences(@PathVariable UUID id) {
        return notificationPreferenceService.getPreferences(id);
    }

    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> unlockUser(@PathVariable UUID id, HttpServletRequest request) {
        String traceId = traceIdProvider != null ? traceIdProvider.resolveTraceId(request) : UUID.randomUUID().toString();
        userService.unlockUser(id, RequestContextUtil.resolveIp(request), RequestContextUtil.resolveUserAgent(request), traceId);
        return ResponseEntity.noContent().build();
    }
}
