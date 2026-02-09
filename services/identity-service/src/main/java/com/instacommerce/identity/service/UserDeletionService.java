package com.instacommerce.identity.service;

import com.instacommerce.identity.domain.model.User;
import com.instacommerce.identity.domain.model.UserStatus;
import com.instacommerce.identity.exception.UserNotFoundException;
import com.instacommerce.identity.repository.RefreshTokenRepository;
import com.instacommerce.identity.repository.UserRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserDeletionService {
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OutboxService outboxService;
    private final AuditService auditService;

    public UserDeletionService(UserRepository userRepository,
                               RefreshTokenRepository refreshTokenRepository,
                               OutboxService outboxService,
                               AuditService auditService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.outboxService = outboxService;
        this.auditService = auditService;
    }

    @Transactional
    public void initiateErasure(UUID userId, String ipAddress, String userAgent, String traceId) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        Instant erasedAt = Instant.now();
        user.setEmail("deleted-" + userId + "@anonymized.local");
        user.setFirstName("DELETED");
        user.setLastName("USER");
        user.setPhone(null);
        user.setPasswordHash("[REDACTED]");
        user.setStatus(UserStatus.DELETED);
        user.setDeletedAt(erasedAt);
        userRepository.save(user);

        refreshTokenRepository.deleteAllByUser_Id(userId);

        outboxService.publish("User", userId.toString(), "UserErased",
            Map.of("userId", userId, "erasedAt", erasedAt));

        auditService.logAction(userId,
            "USER_ERASURE_INITIATED",
            "User",
            userId.toString(),
            Map.of("status", UserStatus.DELETED.name()),
            ipAddress,
            userAgent,
            traceId);
    }
}
