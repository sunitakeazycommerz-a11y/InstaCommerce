package com.instacommerce.routing.security;

import com.instacommerce.routing.config.RoutingProperties;
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
    private final RoutingProperties routingProperties;

    public DefaultJwtService(JwtKeyLoader keyLoader, RoutingProperties routingProperties) {
        this.keyLoader = keyLoader;
        this.routingProperties = routingProperties;
    }

    @Override
    public Jws<Claims> validateAccessToken(String token) {
        return Jwts.parser()
            .verifyWith(keyLoader.getPublicKey())
            .requireIssuer(routingProperties.getJwt().getIssuer())
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
