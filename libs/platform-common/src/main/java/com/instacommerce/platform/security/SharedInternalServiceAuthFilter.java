package com.instacommerce.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates internal service-to-service requests using {@code X-Internal-Service}
 * and {@code X-Internal-Token} headers. Per-service tokens configured under
 * {@code internal.service.allowed-callers} take precedence over the shared token,
 * and blank configured or supplied secrets always fail closed. Accepted callers
 * receive {@code ROLE_INTERNAL_SERVICE} and {@code ROLE_SERVICE_<CALLER>}.
 */
public class SharedInternalServiceAuthFilter extends OncePerRequestFilter {
    public static final String INTERNAL_SERVICE_ROLE = "ROLE_INTERNAL_SERVICE";
    public static final String CALLER_ROLE_PREFIX = "ROLE_SERVICE_";
    public static final String HEADER_INTERNAL_SERVICE = "X-Internal-Service";
    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    private static final Logger log = LoggerFactory.getLogger(SharedInternalServiceAuthFilter.class);

    private final String sharedToken;
    private final Map<String, String> allowedCallers;
    private final boolean strictPerServiceTokens;

    @Autowired
    public SharedInternalServiceAuthFilter(InternalServiceProperties properties) {
        this(
                properties != null ? properties.getToken() : null,
                properties != null ? properties.getAllowedCallers() : Collections.emptyMap(),
                properties != null && properties.isStrictPerServiceTokens());
    }

    public SharedInternalServiceAuthFilter(String sharedToken, Map<String, String> allowedCallers) {
        this(sharedToken, allowedCallers, false);
    }

    public SharedInternalServiceAuthFilter(
            String sharedToken,
            Map<String, String> allowedCallers,
            boolean strictPerServiceTokens) {
        this.sharedToken = sharedToken != null ? sharedToken : "";
        this.allowedCallers = allowedCallers != null ? allowedCallers : Collections.emptyMap();
        this.strictPerServiceTokens = strictPerServiceTokens;
    }

    @Override
    public void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String serviceName = request.getHeader(HEADER_INTERNAL_SERVICE);
        String token = request.getHeader(HEADER_INTERNAL_TOKEN);
        if (serviceName != null && !serviceName.isBlank() && token != null && !token.isBlank()) {
            String trimmedService = serviceName.trim();
            if (isValidToken(trimmedService, token)) {
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        trimmedService, null, authoritiesFor(trimmedService));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } else {
                log.warn("Internal service authentication rejected: service={}, method={}, path={}",
                        trimmedService, request.getMethod(), request.getRequestURI());
            }
        } else if ((serviceName != null && !serviceName.isBlank()) || (token != null && !token.isBlank())) {
            log.warn("Incomplete internal service auth headers: service={}, hasToken={}, method={}, path={}",
                    serviceName, token != null && !token.isBlank(), request.getMethod(), request.getRequestURI());
        }
        filterChain.doFilter(request, response);
    }

    public static List<GrantedAuthority> authoritiesFor(String serviceName) {
        String callerRole = CALLER_ROLE_PREFIX
                + serviceName.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
        return List.of(
                new SimpleGrantedAuthority(INTERNAL_SERVICE_ROLE),
                new SimpleGrantedAuthority(callerRole));
    }

    private boolean isValidToken(String serviceName, String token) {
        if (serviceName.isBlank() || token.isBlank()) {
            return false;
        }
        String perServiceToken = allowedCallers.get(serviceName);
        if (perServiceToken != null && !perServiceToken.isBlank()) {
            return MessageDigest.isEqual(
                    perServiceToken.getBytes(StandardCharsets.UTF_8),
                    token.getBytes(StandardCharsets.UTF_8));
        }
        if (strictPerServiceTokens) {
            log.warn("Caller '{}' rejected because strict per-service tokens are enabled", serviceName);
            return false;
        }
        if (sharedToken.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                sharedToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }
}
