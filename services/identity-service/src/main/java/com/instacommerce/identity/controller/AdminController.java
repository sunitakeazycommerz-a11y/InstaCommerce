package com.instacommerce.identity.controller;

import com.instacommerce.identity.dto.response.NotificationPreferenceResponse;
import com.instacommerce.identity.dto.response.UserResponse;
import com.instacommerce.identity.service.NotificationPreferenceService;
import com.instacommerce.identity.service.UserService;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminController {
    private final UserService userService;
    private final NotificationPreferenceService notificationPreferenceService;

    public AdminController(UserService userService, NotificationPreferenceService notificationPreferenceService) {
        this.userService = userService;
        this.notificationPreferenceService = notificationPreferenceService;
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public Page<UserResponse> listUsers(@PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return userService.listUsers(pageable);
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse getUser(@PathVariable UUID id) {
        return userService.getUserById(id);
    }

    @GetMapping("/users/{id}/notification-preferences")
    @PreAuthorize("hasRole('ADMIN')")
    public NotificationPreferenceResponse getNotificationPreferences(@PathVariable UUID id) {
        return notificationPreferenceService.getPreferences(id);
    }
}
