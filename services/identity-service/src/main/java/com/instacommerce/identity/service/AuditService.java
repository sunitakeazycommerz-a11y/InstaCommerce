package com.instacommerce.identity.service;

import com.instacommerce.identity.domain.model.AuditLog;
import com.instacommerce.identity.repository.AuditLogRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Async("auditExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAction(UUID userId,
                          String action,
                          String entityType,
                          String entityId,
                          Map<String, Object> details,
                          String ipAddress,
                          String userAgent,
                          String traceId) {
        AuditLog log = new AuditLog();
        log.setUserId(userId);
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setDetails(details);
        log.setIpAddress(ipAddress);
        log.setUserAgent(userAgent);
        log.setTraceId(traceId);
        auditLogRepository.save(log);
    }
}
