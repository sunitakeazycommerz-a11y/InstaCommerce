package com.instacommerce.audit.security;

import com.instacommerce.audit.config.AuditProperties;
import com.instacommerce.platform.security.JwtKeyLoader;
import com.instacommerce.platform.security.SecurityRoleUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final AuditProperties auditProperties;
    private volatile RSAPublicKey cachedPublicKey;

    public JwtAuthenticationFilter(AuditProperties auditProperties) {
        this.auditProperties = auditProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            try {
                Claims claims = Jwts.parser()
                        .requireIssuer(auditProperties.getJwt().getIssuer())
                        .verifyWith(loadPublicKey())
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();

                String subject = claims.getSubject();
                List<GrantedAuthority> authorities = SecurityRoleUtils.extractAuthorities(claims.get("roles"));

                if (subject != null && !subject.isBlank()) {
                    List<GrantedAuthority> merged = mergeInternalAuthorities(
                            SecurityContextHolder.getContext().getAuthentication(),
                            authorities);
                    var authentication = new UsernamePasswordAuthenticationToken(subject, token, merged);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (Exception ex) {
                SecurityContextHolder.clearContext();
                log.debug("JWT validation failed: {}", ex.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }

    private RSAPublicKey loadPublicKey() {
        RSAPublicKey key = cachedPublicKey;
        if (key != null) {
            return key;
        }
        synchronized (this) {
            if (cachedPublicKey == null) {
                cachedPublicKey = JwtKeyLoader.parsePublicKey(auditProperties.getJwt().getPublicKey());
            }
            return cachedPublicKey;
        }
    }

    private static List<GrantedAuthority> mergeInternalAuthorities(
            Authentication existingAuth,
            List<GrantedAuthority> jwtAuthorities) {
        if (existingAuth == null || !existingAuth.isAuthenticated()) {
            return jwtAuthorities;
        }
        boolean hasInternalRole = existingAuth.getAuthorities().stream()
                .anyMatch(a -> SecurityRoleUtils.ROLE_INTERNAL_SERVICE.equals(a.getAuthority()));
        if (!hasInternalRole) {
            return jwtAuthorities;
        }
        Set<GrantedAuthority> merged = new LinkedHashSet<>(jwtAuthorities);
        for (GrantedAuthority authority : existingAuth.getAuthorities()) {
            String role = authority.getAuthority();
            if (SecurityRoleUtils.ROLE_INTERNAL_SERVICE.equals(role)
                    || (role != null && role.startsWith("ROLE_SERVICE_"))) {
                merged.add(authority);
            }
        }
        return List.copyOf(merged);
    }
}
