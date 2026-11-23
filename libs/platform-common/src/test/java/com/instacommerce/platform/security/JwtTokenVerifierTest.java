package com.instacommerce.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

@DisplayName("JwtTokenVerifier tests")
class JwtTokenVerifierTest {

    private static KeyPair activePair;
    private static KeyPair rotatedPair;
    private static JwtKeyLoader keyLoader;
    private static JwtTokenVerifier verifier;

    @BeforeAll
    static void setUpKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        activePair = gen.generateKeyPair();
        rotatedPair = gen.generateKeyPair();
        keyLoader = new JwtKeyLoader(
                Base64.getEncoder().encodeToString(activePair.getPublic().getEncoded()),
                List.of(Base64.getEncoder().encodeToString(rotatedPair.getPublic().getEncoded())));
        verifier = new JwtTokenVerifier(
                keyLoader,
                "instacommerce-identity",
                List.of("instacommerce-api", "instacommerce-admin"),
                60L);
    }

    @Test
    @DisplayName("Verifies valid token, rotated key kid, and normalizes roles without double-prefixing")
    void verifiesValidTokenAndRotatedKey() {
        Instant now = Instant.now();
        String kid = JwtKeyLoader.computeKeyId((RSAPublicKey) rotatedPair.getPublic());
        String token = Jwts.builder()
                .issuer("instacommerce-identity")
                .subject("user-42")
                .audience().add("instacommerce-admin").and()
                .claim("roles", List.of("ROLE_ADMIN", "OPS"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300)))
                .header().keyId(kid).and()
                .signWith(rotatedPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        Jws<Claims> verified = verifier.verify(token);
        assertThat(verified.getPayload().getSubject()).isEqualTo("user-42");
        assertThat(verifier.extractAuthorities(verified.getPayload()))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN", "ROLE_OPS");
    }

    @Test
    @DisplayName("Rejects wrong issuer, wrong audience, missing audience, and unknown kid")
    void rejectsInvalidClaimsAndKeys() {
        Instant now = Instant.now();
        String wrongAudience = Jwts.builder()
                .issuer("instacommerce-identity")
                .subject("user-42")
                .audience().add("other-service").and()
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(activePair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        String missingAudience = Jwts.builder()
                .issuer("instacommerce-identity")
                .subject("user-42")
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(activePair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        String unknownKid = Jwts.builder()
                .issuer("instacommerce-identity")
                .subject("user-42")
                .audience().add("instacommerce-api").and()
                .expiration(Date.from(now.plusSeconds(300)))
                .header().keyId("bogus-kid").and()
                .signWith(activePair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        assertThatThrownBy(() -> verifier.verify(wrongAudience)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> verifier.verify(missingAudience)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> verifier.verify(unknownKid)).isInstanceOf(JwtException.class);
    }
}
