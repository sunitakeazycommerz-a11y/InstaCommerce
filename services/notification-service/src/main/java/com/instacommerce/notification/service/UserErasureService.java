package com.instacommerce.notification.service;

import com.instacommerce.notification.repository.NotificationLogRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserErasureService {
    private static final String REDACTED = "[REDACTED]";

    private final NotificationLogRepository notificationLogRepository;
    private final AuditLogService auditLogService;

    public UserErasureService(NotificationLogRepository notificationLogRepository,
                              AuditLogService auditLogService) {
        this.notificationLogRepository = notificationLogRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public void anonymizeUser(UUID userId, Instant erasedAt) {
        int updated = notificationLogRepository.anonymizeByUserId(userId, REDACTED);
        auditLogService.log(userId,
            "USER_ERASURE_APPLIED",
            "NotificationLog",
            userId.toString(),
            Map.of("updated", updated, "erasedAt", erasedAt));
    }
}
