package com.instacommerce.payment.service;

import com.instacommerce.payment.domain.model.AuditLog;
import com.instacommerce.payment.exception.TraceIdProvider;
import com.instacommerce.payment.repository.AuditLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class AuditLogService {
    private static final Logger logger = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;
    private final TraceIdProvider traceIdProvider;
    private final MeterRegistry meterRegistry;
    private final AuditLogService self;

    public AuditLogService(AuditLogRepository auditLogRepository, TraceIdProvider traceIdProvider,
                           MeterRegistry meterRegistry, @Lazy AuditLogService self) {
        this.auditLogRepository = auditLogRepository;
        this.traceIdProvider = traceIdProvider;
        this.meterRegistry = meterRegistry;
        this.self = self;
    }

    /**
     * Safe wrapper for callers — catches audit failures so they never
     * propagate to or poison the caller's transaction.
     * Delegates to {@link #log} via the Spring proxy so REQUIRES_NEW is honoured.
     */
    public void logSafely(UUID userId, String action, String entityType, String entityId, Map<String, Object> details) {
        try {
            self.log(userId, action, entityType, entityId, details);
        } catch (Exception ex) {
            logger.error("Audit log failed for action={} entityId={}: {}", action, entityId, ex.getMessage(), ex);
            meterRegistry.counter("audit.log.failure").increment();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
