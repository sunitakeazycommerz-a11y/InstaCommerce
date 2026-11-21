package com.instacommerce.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.identity.domain.model.RefreshToken;
import com.instacommerce.identity.domain.model.User;
import com.instacommerce.identity.domain.model.UserStatus;
import com.instacommerce.identity.dto.request.LoginRequest;
import com.instacommerce.identity.dto.request.RefreshRequest;
import com.instacommerce.identity.dto.response.AuthResponse;
import com.instacommerce.identity.exception.InvalidCredentialsException;
import com.instacommerce.identity.exception.TokenRevokedException;
import com.instacommerce.identity.infrastructure.metrics.AuthMetrics;
import com.instacommerce.identity.repository.RefreshTokenRepository;
import com.instacommerce.identity.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService Refresh Reuse and Expired Lockout Tests")
class AuthServiceRefreshAndLockoutTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private TokenService tokenService;
    @Mock
    private AuditService auditService;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private UserService userService;
    @Mock
    private OutboxService outboxService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        AuthMetrics authMetrics = new AuthMetrics(new SimpleMeterRegistry());
        authService = new AuthService(
            userRepository,
            refreshTokenRepository,
            passwordEncoder,
            tokenService,
            auditService,
            rateLimitService,
            authMetrics,
            userService,
            outboxService,
            null,
            null);
    }

    @Test
    @DisplayName("refresh atomically consumes token and rotates when revokeIfNotRevoked returns 1")
    void refreshConsumesTokenAtomicallyAndRotates() {
        UUID userId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        User user = activeUser(userId);
        RefreshToken existing = new RefreshToken();
        existing.setId(tokenId);
        existing.setUser(user);
        existing.setRevoked(false);
        existing.setExpiresAt(Instant.now().plusSeconds(3600));

        when(tokenService.hashRefreshToken("raw-refresh")).thenReturn("hash-refresh");
        when(refreshTokenRepository.findByTokenHash("hash-refresh")).thenReturn(Optional.of(existing));
        when(refreshTokenRepository.revokeIfNotRevoked(tokenId)).thenReturn(1);
        when(tokenService.generateRefreshToken()).thenReturn("new-refresh");
        when(tokenService.hashRefreshToken("new-refresh")).thenReturn("new-hash");
        when(tokenService.refreshTokenExpiresAt()).thenReturn(Instant.now().plusSeconds(7200));
        when(tokenService.accessTokenTtlSeconds()).thenReturn(900L);
        when(tokenService.maxRefreshTokens()).thenReturn(5);
        when(tokenService.generateAccessToken(user)).thenReturn("new-access");
        when(refreshTokenRepository.findByUser_IdAndRevokedFalseOrderByCreatedAtAsc(userId)).thenReturn(List.of());

        AuthResponse response = authService.refresh(new RefreshRequest("raw-refresh"), "127.0.0.1", "agent", "trace-1");

        assertThat(response.accessToken()).isEqualTo("new-access");
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
        verify(refreshTokenRepository).revokeIfNotRevoked(tokenId);
    }

    @Test
    @DisplayName("refresh revokes all user tokens when concurrent or already-revoked token reuse is detected")
    void refreshRevokesAllUserTokensOnReuseDetection() {
        UUID userId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        User user = activeUser(userId);
        RefreshToken existing = new RefreshToken();
        existing.setId(tokenId);
        existing.setUser(user);
        existing.setRevoked(false);
        existing.setExpiresAt(Instant.now().plusSeconds(3600));

        when(tokenService.hashRefreshToken("replayed-refresh")).thenReturn("hash-replayed");
        when(refreshTokenRepository.findByTokenHash("hash-replayed")).thenReturn(Optional.of(existing));
        when(refreshTokenRepository.revokeIfNotRevoked(tokenId)).thenReturn(0);

        assertThatThrownBy(() -> authService.refresh(
                new RefreshRequest("replayed-refresh"), "127.0.0.1", "agent", "trace-2"))
            .isInstanceOf(TokenRevokedException.class);

        verify(refreshTokenRepository).revokeAllByUserId(userId);
    }

    @Test
    @DisplayName("login resets failedAttempts and lockedUntil when lockout period has expired")
    void loginResetsExpiredLockoutBeforeCountingAttempt() {
        UUID userId = UUID.randomUUID();
        User user = activeUser(userId);
        user.setPasswordHash("hashed");
        user.setFailedAttempts(5);
        user.setLockedUntil(Instant.now().minusSeconds(60));

        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-pass", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("user@example.com", "wrong-pass", null), "127.0.0.1", "agent", "trace-3"))
            .isInstanceOf(InvalidCredentialsException.class);

        assertThat(user.getFailedAttempts()).isEqualTo(1);
        assertThat(user.getLockedUntil()).isNull();
        verify(userRepository).save(any(User.class));
    }

    private User activeUser(UUID userId) {
        User user = new User();
        user.setId(userId);
        user.setEmail("user@example.com");
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(List.of("CUSTOMER"));
        return user;
    }
}
