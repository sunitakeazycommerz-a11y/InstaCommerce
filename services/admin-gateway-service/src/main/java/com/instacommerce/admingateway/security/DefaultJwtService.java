package com.instacommerce.admingateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class DefaultJwtService implements JwtService {
    private final JwtKeyLoader keyLoader;
    private final String issuer;
    private final String audience;
    private final long clockSkewSeconds;

    public DefaultJwtService(JwtKeyLoader keyLoader,
                             @Value("${admin-gateway.jwt.issuer:instacommerce-identity}") String issuer,
                             @Value("${admin-gateway.jwt.aud:instacommerce-admin}") String audience,
                             @Value("${admin-gateway.jwt.clock-skew-seconds:300}") long clockSkewSeconds) {
        this.keyLoader = keyLoader;
        this.issuer = issuer;
        this.audience = audience;
        this.clockSkewSeconds = clockSkewSeconds;
    }

    @Override
    public Jws<Claims> validateAccessToken(String token) {
        var parser = Jwts.parser()
            .verifyWith(keyLoader.getPublicKey())
            .requireIssuer(issuer)
            .clockSkewSeconds(clockSkewSeconds);

        Jws<Claims> jws = parser
            .build()
            .parseSignedClaims(token);

        Claims claims = jws.getPayload();
        List<String> audiences = claims.getAudience();

        if (audiences == null || !audiences.contains(audience)) {
            throw new JwtException("Token audience '" + audiences + "' does not match expected audience '" + audience + "'");
        }

        return jws;
    }

    @Override
    public Collection<? extends GrantedAuthority> extractAuthorities(Claims claims) {
        Object rolesClaim = claims.get("roles");
        if (rolesClaim instanceof List<?> list) {
            return list.stream()
                .map(role -> Objects.toString(role, null))
                .filter(Objects::nonNull)
                .map(this::toAuthority)
                .collect(Collectors.toList());
        }
        if (rolesClaim instanceof String role) {
            return List.of(toAuthority(role));
        }
        return List.of();
    }

    private SimpleGrantedAuthority toAuthority(String role) {
        String normalized = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return new SimpleGrantedAuthority(normalized);
    }
}
