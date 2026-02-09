package com.instacommerce.identity.service;

import com.instacommerce.identity.domain.model.User;
import com.instacommerce.identity.dto.mapper.UserMapper;
import com.instacommerce.identity.dto.request.ChangePasswordRequest;
import com.instacommerce.identity.dto.response.UserResponse;
import com.instacommerce.identity.exception.InvalidCredentialsException;
import com.instacommerce.identity.exception.UserNotFoundException;
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

    public UserService(UserRepository userRepository,
                       UserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       AuditService auditService) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
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
        auditService.logAction(user.getId(),
            "PASSWORD_CHANGED",
            "User",
            user.getId().toString(),
            Map.of(),
            ipAddress,
            userAgent,
            traceId);
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
