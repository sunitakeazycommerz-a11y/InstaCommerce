package com.instacommerce.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.identity.dto.response.ErrorResponse;
import com.instacommerce.identity.exception.TraceIdProvider;
import com.instacommerce.identity.service.TokenBlocklistService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final TraceIdProvider traceIdProvider;
    private final ObjectMapper objectMapper;
    private final TokenBlocklistService tokenBlocklistService;

    @Autowired
    public JwtAuthenticationFilter(JwtService jwtService,
                                   TraceIdProvider traceIdProvider,
                                   ObjectMapper objectMapper,
                                   ObjectProvider<TokenBlocklistService> tokenBlocklistServiceProvider) {
        this.jwtService = jwtService;
        this.traceIdProvider = traceIdProvider;
        this.objectMapper = objectMapper;
        this.tokenBlocklistService = tokenBlocklistServiceProvider != null
                ? tokenBlocklistServiceProvider.getIfAvailable()
                : null;
    }

    public JwtAuthenticationFilter(JwtService jwtService, TraceIdProvider traceIdProvider, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.traceIdProvider = traceIdProvider;
        this.objectMapper = objectMapper;
        this.tokenBlocklistService = null;
    }

    public JwtAuthenticationFilter(JwtService jwtService,
                                   TraceIdProvider traceIdProvider,
                                   ObjectMapper objectMapper,
                                   TokenBlocklistService tokenBlocklistService) {
        this.jwtService = jwtService;
        this.traceIdProvider = traceIdProvider;
        this.objectMapper = objectMapper;
        this.tokenBlocklistService = tokenBlocklistService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/auth/") || path.startsWith("/api/v1/auth/")) {
            return path.equals("/auth/login")
                || path.equals("/auth/register")
                || path.equals("/auth/refresh")
                || path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/register")
                || path.equals("/api/v1/auth/refresh");
        }
        return path.startsWith("/.well-known/")
            || path.startsWith("/actuator")
            || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = header.substring(7);
        try {
            Jws<Claims> jws = jwtService.validateAccessToken(token);
            Claims claims = jws.getPayload();
            Number iatMs = claims.get("iat_ms", Number.class);
            if (tokenBlocklistService != null && tokenBlocklistService.isTokenRevoked(
                    claims.getId(), claims.getSubject(), claims.getIssuedAt(),
                    iatMs != null ? iatMs.longValue() : null)) {
                writeUnauthorized(request, response, "TOKEN_REVOKED", "Token has been revoked");
                return;
            }
            List<GrantedAuthority> mergedAuthorities = mergeInternalAuthorities(
                    SecurityContextHolder.getContext().getAuthentication(),
                    jwtService.extractAuthorities(claims));
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    claims.getSubject(),
                    token,
                    mergedAuthorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException ex) {
            writeUnauthorized(request, response, "TOKEN_INVALID", "Token is invalid");
        }
    }

    private void writeUnauthorized(HttpServletRequest request,
                                   HttpServletResponse response,
                                   String code,
                                   String message) throws IOException {
        SecurityContextHolder.clearContext();
        String traceId = traceIdProvider.resolveTraceId(request);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-Trace-Id", traceId);
        ErrorResponse error = new ErrorResponse(
            code,
            message,
            traceId,
            Instant.now(),
            List.of());
        objectMapper.writeValue(response.getWriter(), error);
    }

    private static List<GrantedAuthority> mergeInternalAuthorities(
            Authentication existingAuth,
            Collection<? extends GrantedAuthority> jwtAuthorities) {
        if (existingAuth == null || !existingAuth.isAuthenticated()) {
            return List.copyOf(jwtAuthorities);
        }
        boolean hasInternalServiceRole = existingAuth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_INTERNAL_SERVICE".equals(a.getAuthority()));
        if (!hasInternalServiceRole) {
            return List.copyOf(jwtAuthorities);
        }
        Set<GrantedAuthority> merged = new LinkedHashSet<>(jwtAuthorities);
        for (GrantedAuthority authority : existingAuth.getAuthorities()) {
            String role = authority.getAuthority();
            if ("ROLE_INTERNAL_SERVICE".equals(role) || (role != null && role.startsWith("ROLE_SERVICE_"))) {
                merged.add(authority);
            }
        }
        return List.copyOf(merged);
    }
}
