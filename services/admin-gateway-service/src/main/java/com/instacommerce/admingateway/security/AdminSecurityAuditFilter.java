package com.instacommerce.admingateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Audit filter recording all administrative mutation attempts (POST, PUT, DELETE, PATCH)
 * with structured metadata including authenticated principal, roles, target URI, HTTP status, and client IP.
 */
@Component
public class AdminSecurityAuditFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AdminSecurityAuditFilter.class);
    private static final Set<String> MUTATION_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

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
                String uri = request.getRequestURI();

                if (status >= 400) {
                    log.warn("SECURITY_AUDIT_MUTATION_REJECTED: method={} uri={} principal={} roles={} status={} durationMs={} clientIp={}",
                        method, uri, principal, authorities, status, durationMs, clientIp);
                } else {
                    log.info("SECURITY_AUDIT_MUTATION_SUCCESS: method={} uri={} principal={} roles={} status={} durationMs={} clientIp={}",
                        method, uri, principal, authorities, status, durationMs, clientIp);
                }
            }
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
