package com.instacommerce.warehouse.security;

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

@Component
public class InternalServiceAuthFilter extends OncePerRequestFilter {
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
        if (serviceName != null && token != null && isValidToken(serviceName, token)) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                serviceName, null,
                List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
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
        if (sharedToken.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
            sharedToken.getBytes(StandardCharsets.UTF_8),
            token.getBytes(StandardCharsets.UTF_8));
    }
}
