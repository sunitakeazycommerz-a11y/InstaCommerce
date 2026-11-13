package com.instacommerce.notification.service;

import com.instacommerce.notification.domain.model.AuditLog;
import com.instacommerce.notification.exception.TraceIdProvider;
import com.instacommerce.notification.repository.AuditLogRepository;
import com.instacommerce.notification.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ClientIpResolver clientIpResolver;

    public AuditLogService(AuditLogRepository auditLogRepository, TraceIdProvider traceIdProvider) {
        this(auditLogRepository, traceIdProvider, new ClientIpResolver());
    }

    @Autowired
    public AuditLogService(AuditLogRepository auditLogRepository,
                           TraceIdProvider traceIdProvider,
                           ClientIpResolver clientIpResolver) {
        this.auditLogRepository = auditLogRepository;
        this.traceIdProvider = traceIdProvider;
        this.clientIpResolver = clientIpResolver != null ? clientIpResolver : new ClientIpResolver();
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
        return clientIpResolver.resolve(request);
    }

    private String resolveUserAgent(HttpServletRequest request) {
        return request == null ? null : request.getHeader("User-Agent");
    }
}
