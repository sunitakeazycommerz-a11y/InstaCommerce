package com.instacommerce.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.security.interfaces.RSAPublicKey;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;

/**
 * Shared RS256 JWT verifier enforcing issuer, key-id rotation lookup, clock skew,
 * and fail-closed audience validation.
 */
public class JwtTokenVerifier {
    private final JwtKeyLoader keyLoader;
    private final String expectedIssuer;
    private final Set<String> acceptedAudiences;
    private final long clockSkewSeconds;

    public JwtTokenVerifier(
            JwtKeyLoader keyLoader,
            String expectedIssuer,
            Collection<String> acceptedAudiences,
            long clockSkewSeconds) {
        if (keyLoader == null) {
            throw new IllegalArgumentException("JwtKeyLoader must not be null");
        }
        this.keyLoader = keyLoader;
        this.expectedIssuer = expectedIssuer != null ? expectedIssuer.trim() : "";
        this.acceptedAudiences = acceptedAudiences == null
                ? Set.of()
                : acceptedAudiences.stream()
                        .filter(a -> a != null && !a.isBlank())
                        .map(String::trim)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.clockSkewSeconds = Math.max(0L, clockSkewSeconds);
    }

    public Jws<Claims> verify(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtException("Bearer token is missing");
        }
        var builder = Jwts.parser()
                .keyLocator(header -> {
                    if (header instanceof JwsHeader jwsHeader) {
                        String kid = jwsHeader.getKeyId();
                        if (kid != null && !kid.isBlank()) {
                            RSAPublicKey key = keyLoader.getVerificationKey(kid);
                            if (key == null) {
                                throw new JwtException("Unknown JWT key id: " + kid);
                            }
                            return key;
                        }
                    }
                    return keyLoader.getPublicKey();
                })
                .clockSkewSeconds(clockSkewSeconds);
        if (!expectedIssuer.isEmpty()) {
            builder.requireIssuer(expectedIssuer);
        }
        Jws<Claims> jws = builder.build().parseSignedClaims(token.trim());
        if (!acceptedAudiences.isEmpty()
                && !SecurityRoleUtils.hasAcceptedAudience(jws.getPayload().getAudience(), acceptedAudiences)) {
            throw new JwtException("Token audience does not match accepted audiences " + acceptedAudiences);
        }
        return jws;
    }

    public List<GrantedAuthority> extractAuthorities(Claims claims) {
        return claims == null ? List.of() : SecurityRoleUtils.extractAuthorities(claims.get("roles"));
    }
}
