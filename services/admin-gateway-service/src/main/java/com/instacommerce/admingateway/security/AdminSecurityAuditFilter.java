package com.instacommerce.admingateway.security;

import com.instacommerce.admingateway.audit.AdminAuditClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminSecurityAuditFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AdminSecurityAuditFilter.class);
    private static final Set<String> MUTATION_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final AdminAuditClient auditClient;

    public AdminSecurityAuditFilter(AdminAuditClient auditClient) {
        this.auditClient = auditClient;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String method = request.getMethod();
        boolean isMutation = method != null && MUTATION_METHODS.contains(method.toUpperCase());
        long startTime = System.currentTimeMillis();
        Authentication preChainAuth = SecurityContextHolder.getContext().getAuthentication();

        try {
            filterChain.doFilter(request, response);
        } finally {
            if (isMutation) {
                long durationMs = System.currentTimeMillis() - startTime;
                Authentication postChainAuth = SecurityContextHolder.getContext().getAuthentication();
                Authentication auth = (postChainAuth != null && postChainAuth.isAuthenticated())
                        ? postChainAuth
                        : preChainAuth;
                String principal = (auth != null && auth.isAuthenticated() && auth.getName() != null)
                        ? auth.getName()
                        : "anonymous";
                String authorities = (auth != null && auth.getAuthorities() != null)
                        ? auth.getAuthorities().toString()
                        : "[]";
                int status = response.getStatus();
                String clientIp = ClientIpResolver.resolveClientIp(request);
                String remoteAddr = request.getRemoteAddr();
                String uri = request.getRequestURI();
                String correlationId = request.getHeader("X-Correlation-ID");
                if (correlationId == null || correlationId.isBlank()) {
                    correlationId = MDC.get("correlationId");
                }

                if (status >= 400) {
                    log.warn("SECURITY_AUDIT_MUTATION_REJECTED: method={} uri={} principal={} roles={} status={} durationMs={} clientIp={} remoteAddr={}",
                        method, uri, principal, authorities, status, durationMs, clientIp, remoteAddr);
                } else {
                    log.info("SECURITY_AUDIT_MUTATION_SUCCESS: method={} uri={} principal={} roles={} status={} durationMs={} clientIp={} remoteAddr={}",
                        method, uri, principal, authorities, status, durationMs, clientIp, remoteAddr);
                }

                try {
                    publishAuditEvent(
                            principal,
                            authorities,
                            method,
                            uri,
                            status,
                            durationMs,
                            clientIp,
                            remoteAddr,
                            correlationId,
                            request.getHeader("User-Agent"));
                } catch (Exception ex) {
                    log.warn("Failed to dispatch security audit event method={} uri={}", method, uri, ex);
                }
            }
        }
    }

    private void publishAuditEvent(String principal, String authorities, String method, String uri, int status,
                                   long durationMs, String clientIp, String remoteAddr,
                                   String correlationId, String userAgent) {
        if (auditClient == null) {
            return;
        }

        UUID actorId = null;
        try {
            actorId = UUID.fromString(principal);
        } catch (IllegalArgumentException ex) {
            log.debug("Admin principal '{}' is not a UUID, recording actorId as null", principal);
        }

        String safeUri = uri != null ? uri : "";
        String[] uriParts = safeUri.split("/");
        String resourceType = uriParts.length > 3 ? uriParts[3] : "ADMIN_GATEWAY";
        String resourceId = uriParts.length > 4 ? uriParts[4] : safeUri;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("uri", safeUri);
        details.put("principal", principal);
        details.put("roles", authorities);
        details.put("outcome", status >= 400 ? "REJECTED" : "SUCCESS");
        details.put("status", status);
        details.put("durationMs", durationMs);
        details.put("clientIp", clientIp != null ? clientIp : "unknown");
        details.put("remoteAddr", remoteAddr != null ? remoteAddr : "unknown");

        auditClient.publishAuditEvent(
            "ADMIN_MUTATION",
            actorId,
            "ADMIN",
            resourceType,
            resourceId,
            method,
            Map.copyOf(details),
            clientIp,
            userAgent,
            correlationId
        );
    }
}
