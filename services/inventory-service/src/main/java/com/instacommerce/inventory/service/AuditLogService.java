package com.instacommerce.inventory.service;

import com.instacommerce.inventory.domain.model.AuditLog;
import com.instacommerce.inventory.exception.TraceIdProvider;
import com.instacommerce.inventory.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class AuditLogService {
    private final AuditLogRepository auditLogRepository;
    private final TraceIdProvider traceIdProvider;

    public AuditLogService(AuditLogRepository auditLogRepository, TraceIdProvider traceIdProvider) {
        this.auditLogRepository = auditLogRepository;
        this.traceIdProvider = traceIdProvider;
    }

    @Transactional
    public void log(UUID userId, String action, String entityType, String entityId, Map<String, Object> details) {
        AuditLog log = new AuditLog();
        HttpServletRequest request = currentRequest();
        log.setUserId(resolveUserId(userId));
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setDetails(details);
        log.setIpAddress(resolveIp(request));
        log.setUserAgent(resolveUserAgent(request));
        log.setTraceId(traceIdProvider.resolveTraceId(request));
        auditLogRepository.save(log);
    }

    private UUID resolveUserId(UUID userId) {
        if (userId != null) {
            return userId;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        String principal = String.valueOf(authentication.getPrincipal());
        if (principal == null || principal.isBlank() || "anonymousUser".equalsIgnoreCase(principal)) {
            return null;
        }
        try {
            return UUID.fromString(principal);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String resolveIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String resolveUserAgent(HttpServletRequest request) {
        return request == null ? null : request.getHeader("User-Agent");
    }
}
