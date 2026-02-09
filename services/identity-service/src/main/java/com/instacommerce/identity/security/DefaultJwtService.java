package com.instacommerce.identity.security;

import com.instacommerce.identity.config.IdentityProperties;
import com.instacommerce.identity.domain.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class DefaultJwtService implements JwtService {
    private final JwtKeyLoader keyLoader;
    private final IdentityProperties identityProperties;

    public DefaultJwtService(JwtKeyLoader keyLoader, IdentityProperties identityProperties) {
        this.keyLoader = keyLoader;
        this.identityProperties = identityProperties;
    }

    @Override
    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(identityProperties.getToken().getAccessTtlSeconds());
        List<String> roles = user.getRoles() == null ? List.of() : user.getRoles();
        return Jwts.builder()
            .issuer(identityProperties.getJwt().getIssuer())
            .subject(user.getId().toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .claim("roles", roles)
            .claim("aud", "instacommerce-api")
            .claim("email", user.getEmail())
            .id(UUID.randomUUID().toString())
            .header().keyId(keyLoader.getKeyId()).and()
            .signWith(keyLoader.getPrivateKey(), Jwts.SIG.RS256)
            .compact();
    }

    @Override
    public Jws<Claims> validateAccessToken(String token) {
        return Jwts.parser()
            .verifyWith(keyLoader.getPublicKey())
            .requireIssuer(identityProperties.getJwt().getIssuer())
            .requireAudience("instacommerce-api")
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
