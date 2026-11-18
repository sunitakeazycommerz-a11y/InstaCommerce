package com.instacommerce.admingateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminJwtAuthenticationFilter extends OncePerRequestFilter {
    private static final String ADMIN_AUDIENCE = "instacommerce-admin";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public AdminJwtAuthenticationFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
            || path.startsWith("/admin/health")
            || path.startsWith("/admin/metrics")
            || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        HttpServletRequest sanitizedRequest = stripUntrustedHeaders(request);
        String header = sanitizedRequest.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(sanitizedRequest, response);
            return;
        }
        String token = header.substring(7).trim();
        try {
            Jws<Claims> jws = jwtService.validateAccessToken(token);
            Claims claims = jws.getPayload();
            Collection<? extends GrantedAuthority> authorities = jwtService.extractAuthorities(claims);
            Set<String> audiences = claims.getAudience();
            if (audiences != null && !audiences.isEmpty() && !audiences.contains(ADMIN_AUDIENCE)) {
                boolean hasAdminRole = authorities.stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(role -> "ROLE_ADMIN".equals(role) || "ROLE_SUPER_ADMIN".equals(role));
                if (!hasAdminRole) {
                    throw new JwtException("Token lacks required admin role for audience " + audiences);
                }
            }
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                claims.getSubject(),
                token,
                authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(sanitizedRequest));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(sanitizedRequest, response);
        } catch (JwtException | IllegalArgumentException ex) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), Map.of(
                "code", "TOKEN_INVALID",
                "message", "Token is invalid or expired: " + ex.getMessage(),
                "timestamp", Instant.now().toString()));
        }
    }

    private HttpServletRequest stripUntrustedHeaders(HttpServletRequest request) {
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames == null) {
            return request;
        }
        boolean hasUntrusted = false;
        while (headerNames.hasMoreElements()) {
            if (isUntrustedHeader(headerNames.nextElement())) {
                hasUntrusted = true;
                break;
            }
        }
        if (!hasUntrusted) {
            return request;
        }
        return new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                return isUntrustedHeader(name) ? null : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                return isUntrustedHeader(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
            }

            @Override
            public Enumeration<String> getHeaderNames() {
                Enumeration<String> upstream = super.getHeaderNames();
                if (upstream == null) {
                    return Collections.emptyEnumeration();
                }
                List<String> filtered = new ArrayList<>();
                while (upstream.hasMoreElements()) {
                    String name = upstream.nextElement();
                    if (!isUntrustedHeader(name)) {
                        filtered.add(name);
                    }
                }
                return Collections.enumeration(filtered);
            }
        };
    }

    private static boolean isUntrustedHeader(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("x-admin-") || lower.startsWith("x-internal-");
    }
}
