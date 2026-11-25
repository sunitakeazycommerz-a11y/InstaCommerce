package com.instacommerce.identity.controller;

import com.instacommerce.identity.dto.response.NotificationPreferenceResponse;
import com.instacommerce.identity.dto.response.UserResponse;
import com.instacommerce.identity.exception.TraceIdProvider;
import com.instacommerce.identity.service.NotificationPreferenceService;
import com.instacommerce.identity.service.UserService;
import com.instacommerce.identity.util.RequestContextUtil;
import com.instacommerce.platform.web.PageableUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminController {
    private final UserService userService;
    private final NotificationPreferenceService notificationPreferenceService;
    private final TraceIdProvider traceIdProvider;

    public AdminController(UserService userService,
                           NotificationPreferenceService notificationPreferenceService,
                           ObjectProvider<TraceIdProvider> traceIdProvider) {
        this.userService = userService;
        this.notificationPreferenceService = notificationPreferenceService;
        this.traceIdProvider = traceIdProvider != null ? traceIdProvider.getIfAvailable() : null;
    }

    @GetMapping({"/admin/users", "/api/v1/admin/users"})
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public Page<UserResponse> listUsers(@PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return userService.listUsers(PageableUtils.sanitize(pageable));
    }

    @GetMapping({"/admin/users/{id}", "/users/{id}", "/api/v1/users/{id}"})
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'INTERNAL_SERVICE')")
    public UserResponse getUser(@PathVariable UUID id) {
        return userService.getUserById(id);
    }

    @GetMapping({"/admin/users/{id}/notification-preferences", "/users/{id}/notification-preferences", "/api/v1/users/{id}/notification-preferences"})
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'INTERNAL_SERVICE')")
    public NotificationPreferenceResponse getNotificationPreferences(@PathVariable UUID id) {
        return notificationPreferenceService.getPreferences(id);
    }

    @PostMapping({"/admin/users/{id}/unlock", "/api/v1/admin/users/{id}/unlock"})
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Void> unlockUser(@PathVariable UUID id, HttpServletRequest request) {
        String traceId = traceIdProvider != null ? traceIdProvider.resolveTraceId(request) : UUID.randomUUID().toString();
        userService.unlockUser(id, RequestContextUtil.resolveIp(request), RequestContextUtil.resolveUserAgent(request), traceId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping({"/admin/users/{id}/revoke-sessions", "/api/v1/admin/users/{id}/revoke-sessions"})
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Void> revokeSessions(@PathVariable UUID id, HttpServletRequest request) {
        String traceId = traceIdProvider != null ? traceIdProvider.resolveTraceId(request) : UUID.randomUUID().toString();
        userService.revokeUserSessions(id, RequestContextUtil.resolveIp(request), RequestContextUtil.resolveUserAgent(request), traceId);
        return ResponseEntity.noContent().build();
    }

    public record UpdateRolesRequest(@NotEmpty List<String> roles) {}

    @PutMapping({"/admin/users/{id}/roles", "/api/v1/admin/users/{id}/roles"})
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public UserResponse updateUserRoles(@PathVariable UUID id,
                                        @Valid @RequestBody UpdateRolesRequest body,
                                        HttpServletRequest request) {
        String traceId = traceIdProvider != null ? traceIdProvider.resolveTraceId(request) : UUID.randomUUID().toString();
        return userService.updateUserRoles(
            id, body.roles(), RequestContextUtil.resolveIp(request), RequestContextUtil.resolveUserAgent(request), traceId);
    }
}
