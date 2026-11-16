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
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates service-to-service requests carrying {@code X-Internal-Service} and
 * {@code X-Internal-Token}. Per-caller tokens from {@code internal.service.allowed-callers}
 * take precedence over the shared token. Blank headers or blank configured secrets never
 * authenticate. Accepted callers get {@code ROLE_INTERNAL_SERVICE} plus a caller-scoped
 * {@code ROLE_SERVICE_<CALLER>} role (for example {@code ROLE_SERVICE_ORDER_SERVICE}).
 */
@Component
public class InternalServiceAuthFilter extends OncePerRequestFilter {
    public static final String INTERNAL_SERVICE_ROLE = "ROLE_INTERNAL_SERVICE";
    public static final String CALLER_ROLE_PREFIX = "ROLE_SERVICE_";

    private final String sharedToken;
    private final Map<String, String> allowedCallers;

    public InternalServiceAuthFilter(
            @Value("${internal.service.token:}") String sharedToken,
            @Value("#{${internal.service.allowed-callers:{}}}") Map<String, String> allowedCallers) {
        this.sharedToken = sharedToken != null ? sharedToken : "";
        this.allowedCallers = allowedCallers != null ? allowedCallers : Collections.emptyMap();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String serviceName = request.getHeader("X-Internal-Service");
        String token = request.getHeader("X-Internal-Token");
        if (isValidToken(serviceName, token)) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                serviceName.trim(), null, authoritiesFor(serviceName));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }

    static List<GrantedAuthority> authoritiesFor(String serviceName) {
        String callerRole = CALLER_ROLE_PREFIX
            + serviceName.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
        return List.of(new SimpleGrantedAuthority(INTERNAL_SERVICE_ROLE), new SimpleGrantedAuthority(callerRole));
    }

    private boolean isValidToken(String serviceName, String token) {
        if (serviceName == null || token == null || serviceName.isBlank() || token.isBlank()) {
            return false;
        }
        String expected = allowedCallers.get(serviceName.trim());
        if (expected == null || expected.isBlank()) {
            expected = sharedToken;
        }
        if (expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            token.getBytes(StandardCharsets.UTF_8));
    }
}
