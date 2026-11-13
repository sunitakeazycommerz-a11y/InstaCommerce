package com.instacommerce.audit.security;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates internal service-to-service requests using token headers.
 */
@Component
public class InternalServiceAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalServiceAuthFilter.class);

    private final String sharedToken;
    private final Map<String, String> allowedCallers;
    private final boolean strictPerServiceTokens;

    @Autowired
    public InternalServiceAuthFilter(
            @Value("${internal.service.token:}") String sharedToken,
            @Value("#{${internal.service.allowed-callers:{}}}") Map<String, String> allowedCallers,
            @Value("${internal.service.strict-per-service-tokens:false}") boolean strictPerServiceTokens) {
        this.sharedToken = sharedToken != null ? sharedToken : "";
        this.allowedCallers = allowedCallers != null ? allowedCallers : Collections.emptyMap();
        this.strictPerServiceTokens = strictPerServiceTokens;
    }

    public InternalServiceAuthFilter(String sharedToken, Map<String, String> allowedCallers) {
        this(sharedToken, allowedCallers, false);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String serviceName = request.getHeader("X-Internal-Service");
        String token = request.getHeader("X-Internal-Token");
        if (serviceName != null && !serviceName.isBlank() && token != null && !token.isEmpty()) {
            if (isValidToken(serviceName, token)) {
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        serviceName, null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } else {
                log.warn("Internal service authentication rejected: service={}, method={}, path={}",
                        serviceName, request.getMethod(), request.getRequestURI());
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean isValidToken(String serviceName, String token) {
        String perServiceToken = allowedCallers.get(serviceName);
        if (perServiceToken != null && !perServiceToken.isEmpty()) {
            return MessageDigest.isEqual(
                    perServiceToken.getBytes(StandardCharsets.UTF_8),
                    token.getBytes(StandardCharsets.UTF_8));
        }
        if (strictPerServiceTokens || sharedToken.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                sharedToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }
}
