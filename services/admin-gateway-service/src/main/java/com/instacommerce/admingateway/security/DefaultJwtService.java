package com.instacommerce.admingateway.security;

import com.instacommerce.platform.security.SecurityRoleUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class DefaultJwtService implements JwtService {
    private final JwtKeyLoader keyLoader;
    private final String issuer;
    private final Set<String> acceptedAudiences;
    private final long clockSkewSeconds;

    public DefaultJwtService(JwtKeyLoader keyLoader,
                             @Value("${admin-gateway.jwt.issuer:instacommerce-identity}") String issuer,
                             @Value("${admin-gateway.jwt.aud:instacommerce-admin}") String audience,
                             @Value("${admin-gateway.jwt.clock-skew-seconds:300}") long clockSkewSeconds) {
        this(keyLoader, issuer, audience, List.of(audience, "instacommerce-api"), clockSkewSeconds);
    }

    @Autowired
    public DefaultJwtService(JwtKeyLoader keyLoader,
                             @Value("${admin-gateway.jwt.issuer:instacommerce-identity}") String issuer,
                             @Value("${admin-gateway.jwt.aud:instacommerce-admin}") String primaryAudience,
                             @Value("${admin-gateway.jwt.accepted-audiences:instacommerce-admin,instacommerce-api}") List<String> configuredAudiences,
                             @Value("${admin-gateway.jwt.clock-skew-seconds:300}") long clockSkewSeconds) {
        this.keyLoader = keyLoader;
        this.issuer = issuer;
        this.clockSkewSeconds = clockSkewSeconds;
        Set<String> resolved = new LinkedHashSet<>();
        if (primaryAudience != null && !primaryAudience.isBlank()) {
            resolved.add(primaryAudience.trim());
        }
        if (configuredAudiences != null) {
            for (String configured : configuredAudiences) {
                if (configured != null && !configured.isBlank()) {
                    resolved.add(configured.trim());
                }
            }
        }
        this.acceptedAudiences = Set.copyOf(resolved);
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
        Set<String> audiences = claims.getAudience();

        if (!SecurityRoleUtils.hasAcceptedAudience(audiences, acceptedAudiences)) {
            throw new JwtException("Token audience '" + audiences + "' does not match accepted audiences " + acceptedAudiences);
        }

        return jws;
    }

    @Override
    public Collection<? extends GrantedAuthority> extractAuthorities(Claims claims) {
        return SecurityRoleUtils.extractAuthorities(claims.get("roles"));
    }
}
