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
import com.instacommerce.identity.exception.InvalidCredentialsException;
import com.instacommerce.identity.exception.TokenExpiredException;
import com.instacommerce.identity.exception.TokenInvalidException;
import com.instacommerce.identity.exception.TokenRevokedException;
import com.instacommerce.identity.exception.UserAlreadyExistsException;
import com.instacommerce.identity.exception.UserInactiveException;
import com.instacommerce.identity.infrastructure.metrics.AuthMetrics;
import com.instacommerce.identity.repository.RefreshTokenRepository;
import com.instacommerce.identity.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final String TARGET_TYPE_USER = "User";
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MINUTES = 15;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuditService auditService;
    private final RateLimitService rateLimitService;
    private final AuthMetrics authMetrics;
    private final UserService userService;
    private final OutboxService outboxService;
    private final StringRedisTemplate redisTemplate;

    @Autowired
    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       TokenService tokenService,
                       AuditService auditService,
                       RateLimitService rateLimitService,
                       AuthMetrics authMetrics,
                       UserService userService,
                       OutboxService outboxService,
                       ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.rateLimitService = rateLimitService;
        this.authMetrics = authMetrics;
        this.userService = userService;
        this.outboxService = outboxService;
        this.redisTemplate = redisTemplateProvider != null ? redisTemplateProvider.getIfAvailable() : null;
    }

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       TokenService tokenService,
                       AuditService auditService,
                       RateLimitService rateLimitService,
                       AuthMetrics authMetrics,
                       UserService userService,
                       OutboxService outboxService) {
        this(userRepository, refreshTokenRepository, passwordEncoder, tokenService, auditService, rateLimitService, authMetrics, userService, outboxService, null);
    }

    @Transactional
    public AuthResponse register(RegisterRequest request, String ipAddress, String userAgent, String traceId) {
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

        Map<String, Object> registerPayload = new LinkedHashMap<>();
        registerPayload.put("userId", saved.getId().toString());
        registerPayload.put("email", saved.getEmail());
        registerPayload.put("roles", saved.getRoles());
        registerPayload.put("registeredAt", Instant.now().toString());
        outboxService.publish(TARGET_TYPE_USER, saved.getId().toString(), "UserRegistered", registerPayload, traceId);

        String accessToken = tokenService.generateAccessToken(saved);
        String refreshToken = tokenService.generateRefreshToken();
        String refreshHash = tokenService.hashRefreshToken(refreshToken);
        RefreshToken token = new RefreshToken();
        token.setUser(saved);
        token.setTokenHash(refreshHash);
        token.setExpiresAt(tokenService.refreshTokenExpiresAt());
        refreshTokenRepository.save(token);
        enforceMaxRefreshTokens(saved.getId());
        return new AuthResponse(accessToken, refreshToken, tokenService.accessTokenTtlSeconds(), "Bearer");
    }

    @Transactional(noRollbackFor = {InvalidCredentialsException.class, UserInactiveException.class})
    public AuthResponse login(LoginRequest request, String ipAddress, String userAgent, String traceId) {
        rateLimitService.checkLogin(ipAddress);
        return authMetrics.getLoginTimer().record(() -> {
            String email = normalizeEmail(request.email());
            String lockoutKey = "identity:failed_logins:" + email + ":" + (ipAddress != null ? ipAddress : "unknown");

            // Check Redis IP/username rate lockout
            if (redisTemplate != null) {
                try {
                    String attemptsStr = redisTemplate.opsForValue().get(lockoutKey);
                    if (attemptsStr != null && Integer.parseInt(attemptsStr) >= MAX_FAILED_ATTEMPTS) {
                        authMetrics.incrementLoginFailure();
                        auditService.logAction(null,
                            "USER_LOGIN_LOCKED_REDIS",
                            TARGET_TYPE_USER,
                            email,
                            Map.of("reason", "EXCESSIVE_FAILED_ATTEMPTS_PER_IP", "attempts", attemptsStr),
                            ipAddress,
                            userAgent,
                            traceId);
                        throw new UserInactiveException();
                    }
                } catch (UserInactiveException e) {
                    throw e;
                } catch (Exception e) {
                    // Fall back to database lockout
                }
            }

            Optional<User> userOptional = userRepository.findByEmailIgnoreCase(email);
            if (userOptional.isPresent()) {
                User u = userOptional.get();
                if (u.getLockedUntil() != null) {
                    if (u.getLockedUntil().isAfter(Instant.now())) {
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
                    u.setFailedAttempts(0);
                    u.setLockedUntil(null);
                }
            }
            if (userOptional.isEmpty() || !passwordEncoder.matches(request.password(), userOptional.get().getPasswordHash())) {
                if (redisTemplate != null) {
                    try {
                        Long count = redisTemplate.opsForValue().increment(lockoutKey);
                        if (count != null && count == 1) {
                            redisTemplate.expire(lockoutKey, Duration.ofMinutes(LOCKOUT_DURATION_MINUTES));
                        }
                    } catch (Exception e) {
                        // Ignore redis error
                    }
                }

                if (userOptional.isPresent()) {
                    User u = userOptional.get();
                    u.setFailedAttempts(u.getFailedAttempts() + 1);
                    if (u.getFailedAttempts() >= MAX_FAILED_ATTEMPTS) {
                        Instant lockedUntil = Instant.now().plusSeconds(LOCKOUT_DURATION_MINUTES * 60);
                        u.setLockedUntil(lockedUntil);
                        Map<String, Object> lockPayload = new LinkedHashMap<>();
                        lockPayload.put("userId", u.getId().toString());
                        lockPayload.put("email", u.getEmail());
                        lockPayload.put("lockedUntil", lockedUntil.toString());
                        lockPayload.put("failedAttempts", u.getFailedAttempts());
                        lockPayload.put("reason", "EXCESSIVE_FAILED_ATTEMPTS");
                        outboxService.publish(TARGET_TYPE_USER, u.getId().toString(), "AccountLocked", lockPayload, traceId);
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

            if (redisTemplate != null) {
                try {
                    redisTemplate.delete(lockoutKey);
                } catch (Exception e) {
                    // Ignore redis error
                }
            }
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

    @Transactional(noRollbackFor = TokenRevokedException.class)
    public AuthResponse refresh(RefreshRequest request, String ipAddress, String userAgent, String traceId) {
        String refreshHash = tokenService.hashRefreshToken(request.refreshToken());
        RefreshToken existing = refreshTokenRepository.findByTokenHash(refreshHash)
            .orElseThrow(TokenInvalidException::new);
        User user = existing.getUser();
        UUID userId = user.getId();
        if (existing.isRevoked()) {
            handleTokenReuseDetected(userId, existing.getId(), ipAddress, userAgent, traceId);
            throw new TokenRevokedException();
        }
        if (existing.getExpiresAt().isBefore(Instant.now())) {
            throw new TokenExpiredException();
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UserInactiveException();
        }
        int updated = refreshTokenRepository.revokeIfNotRevoked(existing.getId());
        if (updated == 0) {
            handleTokenReuseDetected(userId, existing.getId(), ipAddress, userAgent, traceId);
            throw new TokenRevokedException();
        }
        String newRefresh = tokenService.generateRefreshToken();
        String newRefreshHash = tokenService.hashRefreshToken(newRefresh);
        RefreshToken rotated = new RefreshToken();
        rotated.setUser(user);
        rotated.setTokenHash(newRefreshHash);
        rotated.setDeviceInfo(existing.getDeviceInfo());
        rotated.setExpiresAt(tokenService.refreshTokenExpiresAt());
        refreshTokenRepository.save(rotated);
        enforceMaxRefreshTokens(userId);
        auditService.logAction(userId,
            "TOKEN_REFRESH",
            TARGET_TYPE_USER,
            userId.toString(),
            Map.of("refreshTokenId", existing.getId().toString()),
            ipAddress,
            userAgent,
            traceId);
        String accessToken = tokenService.generateAccessToken(user);
        authMetrics.incrementRefresh();
        return new AuthResponse(accessToken, newRefresh, tokenService.accessTokenTtlSeconds(), "Bearer");
    }

    private void handleTokenReuseDetected(UUID userId, UUID tokenId, String ipAddress, String userAgent, String traceId) {
        int revokedCount = refreshTokenRepository.revokeAllByUserId(userId);
        auditService.logAction(userId,
            "TOKEN_REUSE_DETECTED",
            TARGET_TYPE_USER,
            userId.toString(),
            Map.of("reusedTokenId", tokenId.toString(), "revokedTokens", revokedCount),
            ipAddress,
            userAgent,
            traceId);
    }

    @Transactional
    public void revoke(RevokeRequest request, String ipAddress, String userAgent, String traceId) {
        String refreshHash = tokenService.hashRefreshToken(request.refreshToken());
        RefreshToken existing = refreshTokenRepository.findByTokenHash(refreshHash)
            .orElseThrow(TokenInvalidException::new);
        UUID currentUserId = userService.getCurrentUserId();
        if (!existing.getUser().getId().equals(currentUserId)) {
            throw new AccessDeniedException("Cannot revoke another user's token");
        }
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

    @Transactional
    public void logout(String ipAddress, String userAgent, String traceId) {
        UUID userId = userService.getCurrentUserId();
        int revoked = refreshTokenRepository.revokeAllActiveByUserId(userId);
        authMetrics.incrementRevoke();
        auditService.logAction(userId,
            "USER_LOGOUT",
            TARGET_TYPE_USER,
            userId.toString(),
            Map.of("revokedTokens", revoked),
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
