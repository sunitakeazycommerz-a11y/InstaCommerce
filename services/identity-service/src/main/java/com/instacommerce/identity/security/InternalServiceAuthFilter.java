package com.instacommerce.identity.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates internal service-to-service requests using shared token headers.
 * Defense-in-depth on top of Istio mTLS.
 *
 * Supports per-service tokens via {@code internal.service.allowed-callers} map.
 * Falls back to the shared token during migration (see ADR-010).
 */
@Component
public class InternalServiceAuthFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(InternalServiceAuthFilter.class);
    private final String sharedToken;
    private final Map<String, String> allowedCallers;
    private final boolean strictPerServiceTokens;

    public InternalServiceAuthFilter(
            @Value("${internal.service.token}") String sharedToken,
            @Value("#{${internal.service.allowed-callers:{}}}") Map<String, String> allowedCallers) {
        this(sharedToken, allowedCallers, false);
    }

    public InternalServiceAuthFilter(
            String sharedToken,
            Map<String, String> allowedCallers,
            @Value("${internal.service.strict-per-service-tokens:false}") boolean strictPerServiceTokens) {
        this.sharedToken = sharedToken;
        this.allowedCallers = allowedCallers != null ? allowedCallers : Collections.emptyMap();
        this.strictPerServiceTokens = strictPerServiceTokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String serviceName = request.getHeader("X-Internal-Service");
        String token = request.getHeader("X-Internal-Token");
        if (serviceName != null && token != null) {
            if (isValidToken(serviceName, token)) {
                log.info("Internal service authentication accepted: service={}, method={}, path={}",
                    serviceName, request.getMethod(), request.getRequestURI());
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        serviceName, null,
                        List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } else {
                log.warn("Internal service authentication rejected (invalid token): service={}, method={}, path={}",
                    serviceName, request.getMethod(), request.getRequestURI());
            }
        } else if (serviceName != null || token != null) {
            log.warn("Incomplete internal service auth headers: service={}, hasToken={}, method={}, path={}",
                serviceName, token != null, request.getMethod(), request.getRequestURI());
        }
        filterChain.doFilter(request, response);
    }

    private boolean isValidToken(String serviceName, String token) {
        // Per-service token takes precedence
        String perServiceToken = allowedCallers.get(serviceName);
        if (perServiceToken != null && !perServiceToken.isEmpty()) {
            boolean isValid = MessageDigest.isEqual(
                    perServiceToken.getBytes(StandardCharsets.UTF_8),
                    token.getBytes(StandardCharsets.UTF_8));
            if (isValid) {
                log.debug("Per-service token accepted for: {}", serviceName);
            } else {
                log.debug("Per-service token rejected for: {}", serviceName);
            }
            return isValid;
        }
        // Fall back to shared token during migration
        if (strictPerServiceTokens) {
            log.warn("STRICT_PER_SERVICE_REJECTION: Caller '{}' attempted shared token fallback, but strict per-service tokens are enforced.", serviceName);
            return false;
        }
        boolean isValid = MessageDigest.isEqual(
                sharedToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
        if (isValid) {
            log.warn("DEPRECATION_WARNING: Caller '{}' authenticated via legacy flat shared token! Per-service tokens required under ADR-010/ADR-012.", serviceName);
        } else {
            log.debug("Shared token fallback rejected for: {}", serviceName);
        }
        return isValid;
    }
}
