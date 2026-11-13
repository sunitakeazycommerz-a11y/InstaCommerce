package com.instacommerce.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.notification.domain.model.DeviceToken;
import com.instacommerce.notification.dto.DeviceTokenResponse;
import com.instacommerce.notification.dto.RegisterDeviceTokenRequest;
import com.instacommerce.notification.exception.DeviceTokenNotFoundException;
import com.instacommerce.notification.repository.DeviceTokenRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
class DeviceTokenServiceTest {

    @Mock
    private DeviceTokenRepository deviceTokenRepository;

    @InjectMocks
    private DeviceTokenService deviceTokenService;

    private static Authentication auth(UUID userId, String role) {
        return new UsernamePasswordAuthenticationToken(
            userId.toString(),
            "token",
            List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
    }

    @Test
    @DisplayName("register associates token with authenticated user and normalizes platform")
    void registerCreatesOrUpdatesOwnDeviceToken() {
        UUID userId = UUID.randomUUID();
        when(deviceTokenRepository.findByToken("fcm-abc-123")).thenReturn(Optional.empty());
        when(deviceTokenRepository.save(any(DeviceToken.class))).thenAnswer(inv -> {
            DeviceToken saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        DeviceTokenResponse response = deviceTokenService.register(
            new RegisterDeviceTokenRequest(null, " fcm-abc-123 ", "android"),
            auth(userId, "USER")
        );

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.token()).isEqualTo("fcm-abc-123");
        assertThat(response.platform()).isEqualTo("ANDROID");
        assertThat(response.active()).isTrue();
    }

    @Test
    @DisplayName("register rejects specifying another user's userId for non-admin")
    void registerRejectsDifferentTargetUserForNonAdmin() {
        UUID actorId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();

        assertThatThrownBy(() -> deviceTokenService.register(
            new RegisterDeviceTokenRequest(otherUserId, "fcm-token-1", "IOS"),
            auth(actorId, "USER")
        )).isInstanceOf(AccessDeniedException.class);

        verify(deviceTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("register rejects hijacking an active token owned by another user")
    void registerRejectsHijackingActiveTokenOwnedByAnotherUser() {
        UUID ownerId = UUID.randomUUID();
        UUID attackerId = UUID.randomUUID();
        DeviceToken existing = new DeviceToken();
        existing.setId(UUID.randomUUID());
        existing.setUserId(ownerId);
        existing.setToken("shared-device-token");
        existing.setPlatform("IOS");
        existing.setActive(true);
        when(deviceTokenRepository.findByToken("shared-device-token")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> deviceTokenService.register(
            new RegisterDeviceTokenRequest(null, "shared-device-token", "IOS"),
            auth(attackerId, "USER")
        )).isInstanceOf(AccessDeniedException.class);

        verify(deviceTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("register allows reassigning deactivated token to new user")
    void registerAllowsReassigningInactiveToken() {
        UUID previousOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        DeviceToken existing = new DeviceToken();
        existing.setId(UUID.randomUUID());
        existing.setUserId(previousOwner);
        existing.setToken("recycled-token");
        existing.setPlatform("IOS");
        existing.setActive(false);
        when(deviceTokenRepository.findByToken("recycled-token")).thenReturn(Optional.of(existing));
        when(deviceTokenRepository.save(any(DeviceToken.class))).thenAnswer(inv -> inv.getArgument(0));

        DeviceTokenResponse response = deviceTokenService.register(
            new RegisterDeviceTokenRequest(null, "recycled-token", "web"),
            auth(newOwner, "USER")
        );

        assertThat(response.userId()).isEqualTo(newOwner);
        assertThat(response.platform()).isEqualTo("WEB");
        assertThat(response.active()).isTrue();
    }

    @Test
    @DisplayName("deactivate marks own token inactive and enforces owner/admin BOLA check")
    void deactivateEnforcesOwnershipAndMarksInactive() {
        UUID ownerId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        DeviceToken existing = new DeviceToken();
        existing.setId(UUID.randomUUID());
        existing.setUserId(ownerId);
        existing.setToken("tok-deactivate");
        existing.setActive(true);
        when(deviceTokenRepository.findByToken("tok-deactivate")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> deviceTokenService.deactivate("tok-deactivate", auth(otherId, "USER")))
            .isInstanceOf(AccessDeniedException.class);
        verify(deviceTokenRepository, never()).save(any());

        deviceTokenService.deactivate("tok-deactivate", auth(ownerId, "USER"));
        ArgumentCaptor<DeviceToken> savedCaptor = ArgumentCaptor.forClass(DeviceToken.class);
        verify(deviceTokenRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().isActive()).isFalse();
    }

    @Test
    @DisplayName("deactivate throws DeviceTokenNotFoundException when token is absent")
    void deactivateThrowsWhenTokenNotFound() {
        when(deviceTokenRepository.findByToken("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceTokenService.deactivate("missing", auth(UUID.randomUUID(), "USER")))
            .isInstanceOf(DeviceTokenNotFoundException.class);
    }
}
