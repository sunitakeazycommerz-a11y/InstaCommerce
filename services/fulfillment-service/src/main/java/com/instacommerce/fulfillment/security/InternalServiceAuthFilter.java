package com.instacommerce.fulfillment.security;

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
    private final String sharedToken;
    private final Map<String, String> allowedCallers;

    public InternalServiceAuthFilter(
            @Value("${internal.service.token}") String sharedToken,
            @Value("#{${internal.service.allowed-callers:{}}}") Map<String, String> allowedCallers) {
        this.sharedToken = sharedToken;
        this.allowedCallers = allowedCallers != null ? allowedCallers : Collections.emptyMap();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String serviceName = request.getHeader("X-Internal-Service");
        String token = request.getHeader("X-Internal-Token");
        if (serviceName != null && token != null && isValidToken(serviceName, token)) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    serviceName, null,
                    List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }

    private boolean isValidToken(String serviceName, String token) {
        // Per-service token takes precedence
        String perServiceToken = allowedCallers.get(serviceName);
        if (perServiceToken != null) {
            return MessageDigest.isEqual(
                    perServiceToken.getBytes(StandardCharsets.UTF_8),
                    token.getBytes(StandardCharsets.UTF_8));
        }
        // Fall back to shared token during migration
        return MessageDigest.isEqual(
                sharedToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }
}
