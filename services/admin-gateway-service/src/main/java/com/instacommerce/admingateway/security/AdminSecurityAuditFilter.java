package com.instacommerce.admingateway.security;

import com.instacommerce.admingateway.audit.AdminAuditClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Audit filter recording all administrative mutation attempts (POST, PUT, DELETE, PATCH)
 * with structured metadata including authenticated principal, roles, target URI, HTTP status, and verified client IP.
 * Also publishes audit events to audit-trail-service.
 */
@Component
public class AdminSecurityAuditFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AdminSecurityAuditFilter.class);
    private static final Set<String> MUTATION_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    private static final Pattern IPV4_PATTERN =
            Pattern.compile("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$");
    private static final Pattern IPV6_PATTERN =
            Pattern.compile("^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$|^([0-9a-fA-F]{1,4}:)*:[0-9a-fA-F]{1,4}$");

    private final AdminAuditClient auditClient;

    public AdminSecurityAuditFilter(AdminAuditClient auditClient) {
        this.auditClient = auditClient;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String method = request.getMethod();
        boolean isMutation = MUTATION_METHODS.contains(method.toUpperCase());
        long startTime = System.currentTimeMillis();

        try {
            filterChain.doFilter(request, response);
        } finally {
            if (isMutation) {
                long durationMs = System.currentTimeMillis() - startTime;
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                String principal = (auth != null && auth.isAuthenticated()) ? auth.getName() : "anonymous";
                String authorities = (auth != null) ? auth.getAuthorities().toString() : "[]";
                int status = response.getStatus();
                String clientIp = getClientIp(request);
                String remoteAddr = request.getRemoteAddr();
                String uri = request.getRequestURI();
                String correlationId = request.getHeader("X-Correlation-ID");

                if (status >= 400) {
                    log.warn("SECURITY_AUDIT_MUTATION_REJECTED: method={} uri={} principal={} roles={} status={} durationMs={} clientIp={} remoteAddr={}",
                        method, uri, principal, authorities, status, durationMs, clientIp, remoteAddr);
                } else {
                    log.info("SECURITY_AUDIT_MUTATION_SUCCESS: method={} uri={} principal={} roles={} status={} durationMs={} clientIp={} remoteAddr={}",
                        method, uri, principal, authorities, status, durationMs, clientIp, remoteAddr);
                }

                try {
                    publishAuditEvent(principal, method, uri, status, durationMs, clientIp, remoteAddr, correlationId, request.getHeader("User-Agent"));
                } catch (Exception ex) {
                    log.warn("Failed to dispatch security audit event: {}", ex.getMessage());
                }
            }
        }
    }

    private void publishAuditEvent(String principal, String method, String uri, int status,
                                   long durationMs, String clientIp, String remoteAddr,
                                   String correlationId, String userAgent) {
        if (auditClient == null) {
            return;
        }

        UUID actorId = null;
        try {
            actorId = UUID.fromString(principal);
        } catch (Exception ignored) {
            // principal may be username string e.g. "admin-user-1"
        }

        String[] uriParts = uri.split("/");
        String resourceType = uriParts.length > 3 ? uriParts[3] : "ADMIN_GATEWAY";
        String resourceId = uriParts.length > 4 ? uriParts[4] : uri;

        Map<String, Object> details = Map.of(
            "uri", uri,
            "status", status,
            "durationMs", durationMs,
            "clientIp", clientIp != null ? clientIp : "unknown",
            "remoteAddr", remoteAddr != null ? remoteAddr : "unknown"
        );

        auditClient.publishAuditEvent(
            "ADMIN_MUTATION",
            actorId,
            "ADMIN",
            resourceType,
            resourceId,
            method,
            details,
            clientIp,
            userAgent,
            correlationId
        );
    }

    private String getClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) {
            String candidate = xf.split(",")[0].trim();
            if (isValidIp(candidate)) {
                return candidate;
            }
            log.warn("SECURITY_AUDIT_SPOOFED_IP_DETECTED: invalid X-Forwarded-For header '{}', falling back to remoteAddr {}",
                candidate, request.getRemoteAddr());
        }
        return request.getRemoteAddr();
    }

    private boolean isValidIp(String ip) {
        if (ip == null || ip.isBlank() || ip.length() > 45) {
            return false;
        }
        return IPV4_PATTERN.matcher(ip).matches() || IPV6_PATTERN.matcher(ip).matches();
    }
}
