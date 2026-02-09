package com.instacommerce.order.security;

import com.instacommerce.order.config.OrderProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class DefaultJwtService implements JwtService {
    private final JwtKeyLoader keyLoader;
    private final OrderProperties orderProperties;

    public DefaultJwtService(JwtKeyLoader keyLoader, OrderProperties orderProperties) {
        this.keyLoader = keyLoader;
        this.orderProperties = orderProperties;
    }

    @Override
    public Jws<Claims> validateAccessToken(String token) {
        return Jwts.parser()
            .verifyWith(keyLoader.getPublicKey())
            .requireIssuer(orderProperties.getJwt().getIssuer())
            .build()
            .parseSignedClaims(token);
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
