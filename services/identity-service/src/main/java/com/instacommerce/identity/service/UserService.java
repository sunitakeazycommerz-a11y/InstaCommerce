package com.instacommerce.identity.service;

import com.instacommerce.identity.domain.model.Role;
import com.instacommerce.identity.domain.model.User;
import com.instacommerce.identity.dto.mapper.UserMapper;
import com.instacommerce.identity.dto.request.ChangePasswordRequest;
import com.instacommerce.identity.dto.request.UpdateProfileRequest;
import com.instacommerce.identity.dto.response.UserResponse;
import com.instacommerce.identity.exception.ApiException;
import com.instacommerce.identity.exception.InvalidCredentialsException;
import com.instacommerce.identity.exception.UserNotFoundException;
import com.instacommerce.identity.repository.RefreshTokenRepository;
import com.instacommerce.identity.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final int REDIS_SCAN_BATCH_SIZE = 100;

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OutboxService outboxService;
    private final StringRedisTemplate redisTemplate;
    private final TokenBlocklistService tokenBlocklistService;

    @Autowired
    public UserService(UserRepository userRepository,
                       UserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       AuditService auditService,
                       RefreshTokenRepository refreshTokenRepository,
                       OutboxService outboxService,
                       ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                       ObjectProvider<TokenBlocklistService> tokenBlocklistServiceProvider) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.outboxService = outboxService;
        this.redisTemplate = redisTemplateProvider != null ? redisTemplateProvider.getIfAvailable() : null;
        this.tokenBlocklistService = tokenBlocklistServiceProvider != null
                ? tokenBlocklistServiceProvider.getIfAvailable()
                : null;
    }

    public UserResponse getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
            || "anonymousUser".equalsIgnoreCase(String.valueOf(authentication.getPrincipal()))) {
            throw new AccessDeniedException("Access denied");
        }
        String principal = authentication.getName();
        User user = findByPrincipal(principal);
        return userMapper.toResponse(user);
    }

    public UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
            || "anonymousUser".equalsIgnoreCase(String.valueOf(authentication.getPrincipal()))) {
            throw new AccessDeniedException("Access denied");
        }
        return findByPrincipal(authentication.getName()).getId();
    }

    public Page<UserResponse> listUsers(Pageable pageable) {
        return userRepository.findAll(pageable).map(userMapper::toResponse);
    }

    public UserResponse getUserById(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        return userMapper.toResponse(user);
    }

    public User findCurrentUserEntity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
            || "anonymousUser".equalsIgnoreCase(String.valueOf(authentication.getPrincipal()))) {
            throw new AccessDeniedException("Access denied");
        }
        return findByPrincipal(authentication.getName());
    }

    @Transactional
    public UserResponse updateProfile(UpdateProfileRequest request, String ipAddress, String userAgent, String traceId) {
        User user = findCurrentUserEntity();
        if (request.firstName() != null) {
            user.setFirstName(request.firstName().trim());
        }
        if (request.lastName() != null) {
            user.setLastName(request.lastName().trim());
        }
        if (request.phone() != null) {
            user.setPhone(request.phone().trim());
        }
        User saved = userRepository.save(user);
        auditService.logAction(saved.getId(),
            "PROFILE_UPDATED",
            "User",
            saved.getId().toString(),
            Map.of(),
            ipAddress,
            userAgent,
            traceId);
        return userMapper.toResponse(saved);
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request, String ipAddress, String userAgent, String traceId) {
        User user = findCurrentUserEntity();
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        refreshTokenRepository.revokeAllByUserId(user.getId());
        if (tokenBlocklistService != null) {
            tokenBlocklistService.revokeAllUserTokens(user.getId());
        }
        auditService.logAction(user.getId(),
            "PASSWORD_CHANGED",
            "User",
            user.getId().toString(),
            Map.of(),
            ipAddress,
            userAgent,
            traceId);
    }

    @Transactional
    public void revokeUserSessions(UUID userId, String ipAddress, String userAgent, String traceId) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        int revokedTokens = refreshTokenRepository.revokeAllByUserId(user.getId());
        if (tokenBlocklistService != null) {
            tokenBlocklistService.revokeAllUserTokens(user.getId());
        }
        auditService.logAction(user.getId(),
            "USER_SESSIONS_REVOKED_BY_ADMIN",
            "User",
            user.getId().toString(),
            Map.of("revokedTokens", revokedTokens),
            ipAddress,
            userAgent,
            traceId);
        if (outboxService != null) {
            outboxService.publish("User", user.getId().toString(), "UserSessionsRevoked",
                Map.of("userId", user.getId().toString(), "revokedTokens", revokedTokens,
                    "revokedAt", Instant.now().toString()),
                traceId);
        }
    }

    @Transactional
    public UserResponse updateUserRoles(UUID userId, List<String> roles,
                                        String ipAddress, String userAgent, String traceId) {
        if (roles == null || roles.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "At least one role is required");
        }
        Set<String> normalizedRoles = new LinkedHashSet<>();
        for (String roleName : roles) {
            if (roleName == null || roleName.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "Role must not be blank");
            }
            String candidate = roleName.trim().toUpperCase(Locale.ROOT);
            try {
                Role.valueOf(candidate);
            } catch (IllegalArgumentException ex) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "Unsupported role: " + roleName);
            }
            normalizedRoles.add(candidate);
        }
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        List<String> updatedRoles = List.copyOf(normalizedRoles);
        user.setRoles(updatedRoles);
        User saved = userRepository.save(user);
        refreshTokenRepository.revokeAllByUserId(saved.getId());
        if (tokenBlocklistService != null) {
            tokenBlocklistService.revokeAllUserTokens(saved.getId());
        }
        auditService.logAction(saved.getId(),
            "USER_ROLES_UPDATED_BY_ADMIN",
            "User",
            saved.getId().toString(),
            Map.of("roles", updatedRoles),
            ipAddress,
            userAgent,
            traceId);
        return userMapper.toResponse(saved);
    }

    @Transactional
    public void unlockUser(UUID userId, String ipAddress, String userAgent, String traceId) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        if (redisTemplate != null) {
            String pattern = "identity:failed_logins:" + user.getEmail() + ":*";
            ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(REDIS_SCAN_BATCH_SIZE)
                .build();
            try (Cursor<String> cursor = redisTemplate.scan(options)) {
                if (cursor != null) {
                    List<String> keysBatch = new ArrayList<>(REDIS_SCAN_BATCH_SIZE);
                    while (cursor.hasNext()) {
                        keysBatch.add(cursor.next());
                        if (keysBatch.size() >= REDIS_SCAN_BATCH_SIZE) {
                            redisTemplate.delete(keysBatch);
                            keysBatch.clear();
                        }
                    }
                    if (!keysBatch.isEmpty()) {
                        redisTemplate.delete(keysBatch);
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to scan and clear Redis failed login lockout keys for userId={}", user.getId(), ex);
            }
        }

        auditService.logAction(user.getId(),
            "USER_UNLOCKED_BY_ADMIN",
            "User",
            user.getId().toString(),
            Map.of("reason", "CUSTOMER_SUPPORT_UNLOCK"),
            ipAddress,
            userAgent,
            traceId);

        if (outboxService != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", user.getId().toString());
            payload.put("email", user.getEmail());
            payload.put("unlockedAt", Instant.now().toString());
            payload.put("action", "ADMIN_UNLOCK");
            outboxService.publish("User", user.getId().toString(), "AccountUnlocked", payload, traceId);
        }
    }

    private User findByPrincipal(String principal) {
        if (principal == null || principal.isBlank()) {
            throw new UserNotFoundException();
        }
        try {
            UUID userId = UUID.fromString(principal);
            return userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        } catch (IllegalArgumentException ex) {
            return userRepository.findByEmailIgnoreCase(principal).orElseThrow(UserNotFoundException::new);
        }
    }
}
