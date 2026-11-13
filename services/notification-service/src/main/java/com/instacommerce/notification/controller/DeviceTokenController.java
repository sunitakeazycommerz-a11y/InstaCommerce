package com.instacommerce.notification.controller;

import com.instacommerce.notification.dto.DeviceTokenResponse;
import com.instacommerce.notification.dto.RegisterDeviceTokenRequest;
import com.instacommerce.notification.service.DeviceTokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notifications/devices")
public class DeviceTokenController {
    private final DeviceTokenService deviceTokenService;

    public DeviceTokenController(DeviceTokenService deviceTokenService) {
        this.deviceTokenService = deviceTokenService;
    }

    @PostMapping
    public ResponseEntity<DeviceTokenResponse> register(@Valid @RequestBody RegisterDeviceTokenRequest request,
                                                        Authentication authentication) {
        DeviceTokenResponse response = deviceTokenService.register(request, authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{token}")
    public ResponseEntity<Void> deactivate(@PathVariable String token, Authentication authentication) {
        deviceTokenService.deactivate(token, authentication);
        return ResponseEntity.noContent().build();
    }
}
