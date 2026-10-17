package com.instacommerce.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instacommerce.identity.config.IdentityProperties;
import com.instacommerce.identity.domain.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwksKeyRotationTest {

    private KeyPair activeKeyPair;
    private KeyPair previousKeyPair;
    private KeyPair unknownKeyPair;
    private IdentityProperties properties;
    private JwtKeyLoader keyLoader;
    private DefaultJwtService jwtService;
    private JwksController jwksController;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        activeKeyPair = kpg.generateKeyPair();
        previousKeyPair = kpg.generateKeyPair();
        unknownKeyPair = kpg.generateKeyPair();

        properties = new IdentityProperties();
        properties.getJwt().setIssuer("instacommerce-identity");
        properties.getJwt().setPrivateKey(toPem(activeKeyPair.getPrivate().getEncoded(), "PRIVATE KEY"));
        properties.getJwt().setPublicKey(toPem(activeKeyPair.getPublic().getEncoded(), "PUBLIC KEY"));
        properties.getJwt().setPreviousPublicKeys(List.of(
            toPem(previousKeyPair.getPublic().getEncoded(), "PUBLIC KEY")
        ));

        keyLoader = new JwtKeyLoader(properties);
        jwtService = new DefaultJwtService(keyLoader, properties);
        jwksController = new JwksController(keyLoader);
    }

    @Test
    @DisplayName("JwksController publishes both active key and previous grace period keys")
    void shouldPublishMultipleKeysInJwks() {
        Map<String, Object> jwks = jwksController.jwks();
        assertThat(jwks).containsKey("keys");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
        assertThat(keys).hasSize(2);

        // Verify active key is present
        assertThat(keys.get(0).get("kid")).isEqualTo(keyLoader.getKeyId());
        assertThat(keys.get(0).get("alg")).isEqualTo("RS256");
        assertThat(keys.get(0).get("kty")).isEqualTo("RSA");

        // Verify previous grace period key is present with distinct kid
        assertThat(keys.get(1).get("kid")).isNotNull();
        assertThat(keys.get(1).get("kid")).isNotEqualTo(keyLoader.getKeyId());
    }

    @Test
    @DisplayName("DefaultJwtService signs new tokens with active key and active keyId")
    void shouldSignTokenWithActiveKey() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("user@example.com");
        user.setRoles(List.of("CUSTOMER"));

        String token = jwtService.generateAccessToken(user);
        assertThat(token).isNotBlank();

        Jws<Claims> claims = jwtService.validateAccessToken(token);
        assertThat(claims.getPayload().getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.getHeader().getKeyId()).isEqualTo(keyLoader.getKeyId());
    }

    @Test
    @DisplayName("DefaultJwtService successfully validates tokens signed by previous key N-1 during grace period")
    void shouldValidateTokenSignedByPreviousKeyDuringGracePeriod() {
        // Create token signed by previous keypair N-1 with its kid
        String previousKid = keyLoader.getAllPublicKeys().get(1).kid();
        Instant now = Instant.now();

        String tokenSignedByPreviousKey = Jwts.builder()
            .issuer("instacommerce-identity")
            .subject("legacy-user-123")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(3600)))
            .claim("roles", List.of("CUSTOMER"))
            .claim("aud", "instacommerce-api")
            .id(UUID.randomUUID().toString())
            .header().keyId(previousKid).and()
            .signWith(previousKeyPair.getPrivate(), Jwts.SIG.RS256)
            .compact();

        Jws<Claims> parsed = jwtService.validateAccessToken(tokenSignedByPreviousKey);
        assertThat(parsed.getPayload().getSubject()).isEqualTo("legacy-user-123");
        assertThat(parsed.getHeader().getKeyId()).isEqualTo(previousKid);
    }

    @Test
    @DisplayName("DefaultJwtService rejects tokens signed by an unknown key")
    void shouldRejectTokenSignedByUnknownKey() {
        Instant now = Instant.now();
        String unknownToken = Jwts.builder()
            .issuer("instacommerce-identity")
            .subject("attacker")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(3600)))
            .claim("roles", List.of("SUPER_ADMIN"))
            .claim("aud", "instacommerce-api")
            .id(UUID.randomUUID().toString())
            .header().keyId("unknown-key-id").and()
            .signWith(unknownKeyPair.getPrivate(), Jwts.SIG.RS256)
            .compact();

        assertThatThrownBy(() -> jwtService.validateAccessToken(unknownToken))
            .isInstanceOf(JwtException.class);
    }

    private static String toPem(byte[] encoded, String type) {
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
        return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
    }
}
