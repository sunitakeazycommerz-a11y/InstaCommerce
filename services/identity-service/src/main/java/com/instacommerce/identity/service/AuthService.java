package com.instacommerce.identity.service;

import com.instacommerce.identity.domain.model.RefreshToken;
import com.instacommerce.identity.domain.model.Role;
import com.instacommerce.identity.domain.model.User;
import com.instacommerce.identity.domain.model.UserStatus;
import com.instacommerce.identity.dto.request.LoginRequest;
import com.instacommerce.identity.dto.request.RefreshRequest;
import com.instacommerce.identity.dto.request.RegisterRequest;
import com.instacommerce.identity.dto.request.RevokeRequest;
import com.instacommerce.identity.dto.response.AuthResponse;
import com.instacommerce.identity.dto.response.RegisterResponse;
import com.instacommerce.identity.exception.InvalidCredentialsException;
import com.instacommerce.identity.exception.TokenExpiredException;
import com.instacommerce.identity.exception.TokenInvalidException;
import com.instacommerce.identity.exception.TokenRevokedException;
import com.instacommerce.identity.exception.UserAlreadyExistsException;
import com.instacommerce.identity.exception.UserInactiveException;
import com.instacommerce.identity.infrastructure.metrics.AuthMetrics;
import com.instacommerce.identity.repository.RefreshTokenRepository;
import com.instacommerce.identity.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final String TARGET_TYPE_USER = "User";
    private static final int MAX_FAILED_ATTEMPTS = 10;
    private static final long LOCKOUT_DURATION_MINUTES = 30;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuditService auditService;
    private final RateLimitService rateLimitService;
    private final AuthMetrics authMetrics;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       TokenService tokenService,
                       AuditService auditService,
                       RateLimitService rateLimitService,
                       AuthMetrics authMetrics) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.rateLimitService = rateLimitService;
        this.authMetrics = authMetrics;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request, String ipAddress, String userAgent, String traceId) {
        rateLimitService.checkRegister(ipAddress);
        String email = normalizeEmail(request.email());
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new UserAlreadyExistsException();
        }
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRoles(new ArrayList<>(List.of(Role.CUSTOMER.name())));
        user.setStatus(UserStatus.ACTIVE);
        User saved = userRepository.save(user);
        authMetrics.incrementRegister();
        auditService.logAction(saved.getId(),
            "USER_REGISTERED",
            TARGET_TYPE_USER,
            saved.getId().toString(),
            Map.of(),
            ipAddress,
            userAgent,
            traceId);
        return new RegisterResponse(saved.getId(), "Registration successful");
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ipAddress, String userAgent, String traceId) {
        rateLimitService.checkLogin(ipAddress);
        return authMetrics.getLoginTimer().record(() -> {
            String email = normalizeEmail(request.email());
            Optional<User> userOptional = userRepository.findByEmailIgnoreCase(email);
            if (userOptional.isPresent()) {
                User u = userOptional.get();
                if (u.getLockedUntil() != null && u.getLockedUntil().isAfter(Instant.now())) {
                    authMetrics.incrementLoginFailure();
                    auditService.logAction(u.getId(),
                        "USER_LOGIN_LOCKED",
                        TARGET_TYPE_USER,
                        u.getId().toString(),
                        Map.of("lockedUntil", u.getLockedUntil().toString()),
                        ipAddress,
                        userAgent,
                        traceId);
                    throw new UserInactiveException();
                }
            }
            if (userOptional.isEmpty() || !passwordEncoder.matches(request.password(), userOptional.get().getPasswordHash())) {
                if (userOptional.isPresent()) {
                    User u = userOptional.get();
                    u.setFailedAttempts(u.getFailedAttempts() + 1);
                    if (u.getFailedAttempts() >= MAX_FAILED_ATTEMPTS) {
                        u.setLockedUntil(Instant.now().plusSeconds(LOCKOUT_DURATION_MINUTES * 60));
                    }
                    userRepository.save(u);
                }
                authMetrics.incrementLoginFailure();
                auditService.logAction(userOptional.map(User::getId).orElse(null),
                    "USER_LOGIN_FAILED",
                    TARGET_TYPE_USER,
                    userOptional.map(user -> user.getId().toString()).orElse(null),
                    Map.of("reason", "INVALID_CREDENTIALS"),
                    ipAddress,
                    userAgent,
                    traceId);
                throw new InvalidCredentialsException();
            }
            User user = userOptional.get();
            if (user.getStatus() != UserStatus.ACTIVE) {
                authMetrics.incrementLoginFailure();
                auditService.logAction(user.getId(),
                    "USER_LOGIN_BLOCKED",
                    TARGET_TYPE_USER,
                    user.getId().toString(),
                    Map.of("status", user.getStatus().name()),
                    ipAddress,
                    userAgent,
                    traceId);
                throw new UserInactiveException();
            }
            user.setFailedAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
            String accessToken = tokenService.generateAccessToken(user);
            String refreshToken = tokenService.generateRefreshToken();
            String refreshHash = tokenService.hashRefreshToken(refreshToken);
            RefreshToken token = new RefreshToken();
            token.setUser(user);
            token.setTokenHash(refreshHash);
            token.setDeviceInfo(request.deviceInfo());
            token.setExpiresAt(tokenService.refreshTokenExpiresAt());
            refreshTokenRepository.save(token);
            enforceMaxRefreshTokens(user.getId());
            authMetrics.incrementLoginSuccess();
            auditService.logAction(user.getId(),
                "USER_LOGIN_SUCCESS",
                TARGET_TYPE_USER,
                user.getId().toString(),
                Map.of(),
                ipAddress,
                userAgent,
                traceId);
            return new AuthResponse(accessToken, refreshToken, tokenService.accessTokenTtlSeconds(), "Bearer");
        });
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request, String ipAddress, String userAgent, String traceId) {
        String refreshHash = tokenService.hashRefreshToken(request.refreshToken());
        RefreshToken existing = refreshTokenRepository.findByTokenHash(refreshHash)
            .orElseThrow(TokenInvalidException::new);
        if (existing.isRevoked()) {
            throw new TokenRevokedException();
        }
        if (existing.getExpiresAt().isBefore(Instant.now())) {
            throw new TokenExpiredException();
        }
        if (existing.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new UserInactiveException();
        }
        existing.setRevoked(true);
        refreshTokenRepository.save(existing);
        String newRefresh = tokenService.generateRefreshToken();
        String newRefreshHash = tokenService.hashRefreshToken(newRefresh);
        RefreshToken rotated = new RefreshToken();
        rotated.setUser(existing.getUser());
        rotated.setTokenHash(newRefreshHash);
        rotated.setDeviceInfo(existing.getDeviceInfo());
        rotated.setExpiresAt(tokenService.refreshTokenExpiresAt());
        refreshTokenRepository.save(rotated);
        enforceMaxRefreshTokens(existing.getUser().getId());
        auditService.logAction(existing.getUser().getId(),
            "TOKEN_REFRESH",
            TARGET_TYPE_USER,
            existing.getUser().getId().toString(),
            Map.of("refreshTokenId", existing.getId().toString()),
            ipAddress,
            userAgent,
            traceId);
        String accessToken = tokenService.generateAccessToken(existing.getUser());
        authMetrics.incrementRefresh();
        return new AuthResponse(accessToken, newRefresh, tokenService.accessTokenTtlSeconds(), "Bearer");
    }

    @Transactional
    public void revoke(RevokeRequest request, String ipAddress, String userAgent, String traceId) {
        String refreshHash = tokenService.hashRefreshToken(request.refreshToken());
        RefreshToken existing = refreshTokenRepository.findByTokenHash(refreshHash)
            .orElseThrow(TokenInvalidException::new);
        if (!existing.isRevoked()) {
            existing.setRevoked(true);
            refreshTokenRepository.save(existing);
        }
        authMetrics.incrementRevoke();
        auditService.logAction(existing.getUser().getId(),
            "TOKEN_REVOKE",
            "RefreshToken",
            existing.getId().toString(),
            Map.of("userId", existing.getUser().getId().toString()),
            ipAddress,
            userAgent,
            traceId);
    }

    private void enforceMaxRefreshTokens(UUID userId) {
        int maxTokens = tokenService.maxRefreshTokens();
        if (maxTokens <= 0) {
            return;
        }
        List<RefreshToken> activeTokens = refreshTokenRepository.findByUser_IdAndRevokedFalseOrderByCreatedAtAsc(userId);
        if (activeTokens.size() > maxTokens) {
            List<RefreshToken> toRemove = activeTokens.subList(0, activeTokens.size() - maxTokens);
            refreshTokenRepository.deleteAll(toRemove);
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
