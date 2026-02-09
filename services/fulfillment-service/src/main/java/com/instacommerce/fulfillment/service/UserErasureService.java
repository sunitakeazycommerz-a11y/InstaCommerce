package com.instacommerce.fulfillment.service;

import com.instacommerce.fulfillment.repository.PickTaskRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserErasureService {
    private static final UUID ANON_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final PickTaskRepository pickTaskRepository;
    private final AuditLogService auditLogService;

    public UserErasureService(PickTaskRepository pickTaskRepository, AuditLogService auditLogService) {
        this.pickTaskRepository = pickTaskRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public void anonymizeUser(UUID userId, Instant erasedAt) {
        int updated = pickTaskRepository.anonymizeByUserId(userId, ANON_USER_ID);
        auditLogService.log(userId,
            "USER_ERASURE_APPLIED",
            "PickTask",
            userId.toString(),
            Map.of("updated", updated, "erasedAt", erasedAt, "placeholderUserId", ANON_USER_ID));
    }
}
