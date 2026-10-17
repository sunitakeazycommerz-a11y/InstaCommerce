package com.instacommerce.identity.service;

import com.instacommerce.identity.domain.model.User;
import com.instacommerce.identity.dto.mapper.UserMapper;
import com.instacommerce.identity.dto.request.ChangePasswordRequest;
import com.instacommerce.identity.dto.response.UserResponse;
import com.instacommerce.identity.exception.InvalidCredentialsException;
import com.instacommerce.identity.exception.UserNotFoundException;
import com.instacommerce.identity.repository.RefreshTokenRepository;
import com.instacommerce.identity.repository.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OutboxService outboxService;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public UserService(UserRepository userRepository,
                       UserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       AuditService auditService,
                       RefreshTokenRepository refreshTokenRepository,
                       OutboxService outboxService,
                       org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redisTemplateProvider) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.outboxService = outboxService;
        this.redisTemplate = redisTemplateProvider != null ? redisTemplateProvider.getIfAvailable() : null;
    }

    public UserService(UserRepository userRepository,
                       UserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       AuditService auditService,
                       RefreshTokenRepository refreshTokenRepository) {
        this(userRepository, userMapper, passwordEncoder, auditService, refreshTokenRepository, null, null);
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
    public void changePassword(ChangePasswordRequest request, String ipAddress, String userAgent, String traceId) {
        User user = findCurrentUserEntity();
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        refreshTokenRepository.revokeAllActiveByUserId(user.getId());
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
    public void unlockUser(UUID userId, String ipAddress, String userAgent, String traceId) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        if (redisTemplate != null) {
            try {
                java.util.Set<String> keys = redisTemplate.keys("identity:failed_logins:" + user.getEmail() + ":*");
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            } catch (Exception ignored) {
                // Ignore Redis errors
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
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("userId", user.getId().toString());
            payload.put("email", user.getEmail());
            payload.put("unlockedAt", java.time.Instant.now().toString());
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
