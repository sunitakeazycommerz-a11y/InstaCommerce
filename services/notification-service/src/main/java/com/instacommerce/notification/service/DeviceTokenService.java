package com.instacommerce.notification.service;

import com.instacommerce.notification.domain.model.DeviceToken;
import com.instacommerce.notification.dto.DeviceTokenResponse;
import com.instacommerce.notification.dto.RegisterDeviceTokenRequest;
import com.instacommerce.notification.exception.DeviceTokenNotFoundException;
import com.instacommerce.notification.repository.DeviceTokenRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceTokenService {
    private final DeviceTokenRepository deviceTokenRepository;

    public DeviceTokenService(DeviceTokenRepository deviceTokenRepository) {
        this.deviceTokenRepository = deviceTokenRepository;
    }

    @Transactional
    public DeviceTokenResponse register(RegisterDeviceTokenRequest request, Authentication authentication) {
        boolean admin = isAdmin(authentication);
        UUID targetUserId = resolveTargetUserId(request.userId(), authentication, admin);
        String normalizedToken = request.token().trim();
        String normalizedPlatform = normalizePlatform(request.platform());

        DeviceToken deviceToken = deviceTokenRepository.findByToken(normalizedToken).orElseGet(DeviceToken::new);
        if (deviceToken.getId() != null
                && !deviceToken.getUserId().equals(targetUserId)
                && deviceToken.isActive()
                && !admin) {
            throw new AccessDeniedException("Device token belongs to another user");
        }

        deviceToken.setUserId(targetUserId);
        deviceToken.setToken(normalizedToken);
        deviceToken.setPlatform(normalizedPlatform);
        deviceToken.setActive(true);
        DeviceToken saved = deviceTokenRepository.save(deviceToken);
        return toResponse(saved);
    }

    @Transactional
    public void deactivate(String token, Authentication authentication) {
        DeviceToken deviceToken = deviceTokenRepository.findByToken(token.trim())
            .orElseThrow(() -> new DeviceTokenNotFoundException("Device token not found"));

        if (!isAdmin(authentication)) {
            UUID actorId = parseActorUuid(authentication);
            if (!deviceToken.getUserId().equals(actorId)) {
                throw new AccessDeniedException("Cannot deactivate another user's device token");
            }
        }

        if (deviceToken.isActive()) {
            deviceToken.setActive(false);
            deviceTokenRepository.save(deviceToken);
        }
    }

    private UUID resolveTargetUserId(UUID requestedUserId, Authentication authentication, boolean admin) {
        if (requestedUserId != null) {
            if (!admin) {
                UUID actorId = parseActorUuid(authentication);
                if (!actorId.equals(requestedUserId)) {
                    throw new AccessDeniedException("Cannot register device token for another user");
                }
            }
            return requestedUserId;
        }
        return parseActorUuid(authentication);
    }

    private UUID parseActorUuid(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AccessDeniedException("Authenticated user identity is required");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            throw new AccessDeniedException("Authenticated principal is not a valid user UUID");
        }
    }

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    private String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return "UNKNOWN";
        }
        return platform.trim().toUpperCase(Locale.ROOT);
    }

    private DeviceTokenResponse toResponse(DeviceToken entity) {
        return new DeviceTokenResponse(
            entity.getId(),
            entity.getUserId(),
            entity.getToken(),
            entity.getPlatform(),
            entity.isActive(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
