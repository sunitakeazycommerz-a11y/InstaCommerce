package com.instacommerce.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JwtKeyLoader tests")
class JwtKeyLoaderTest {

    private static KeyPair primaryPair;
    private static KeyPair previousPair;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        primaryPair = gen.generateKeyPair();
        previousPair = gen.generateKeyPair();
    }

    @Test
    @DisplayName("Parses PEM, raw Base64, URL-safe Base64, and derives public key from PKCS8 private key")
    void parsesAllSupportedKeyFormats() {
        String rawB64 = Base64.getEncoder().encodeToString(primaryPair.getPublic().getEncoded());
        String urlB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(primaryPair.getPublic().getEncoded());
        String singleLinePem = "-----BEGIN PUBLIC KEY-----" + rawB64 + "-----END PUBLIC KEY-----";
        String multiLinePem = "-----BEGIN PUBLIC KEY-----\n" + rawB64 + "\n-----END PUBLIC KEY-----\n";
        String privateB64 = Base64.getEncoder().encodeToString(primaryPair.getPrivate().getEncoded());

        assertThat(JwtKeyLoader.parsePublicKey(rawB64)).isEqualTo(primaryPair.getPublic());
        assertThat(JwtKeyLoader.parsePublicKey(urlB64)).isEqualTo(primaryPair.getPublic());
        assertThat(JwtKeyLoader.parsePublicKey(singleLinePem)).isEqualTo(primaryPair.getPublic());
        assertThat(JwtKeyLoader.parsePublicKey(multiLinePem)).isEqualTo(primaryPair.getPublic());

        RSAPrivateKey parsedPrivate = JwtKeyLoader.parsePrivateKey(privateB64);
        assertThat(JwtKeyLoader.resolvePublicKey("", parsedPrivate)).isEqualTo(primaryPair.getPublic());
    }

    @Test
    @DisplayName("Supports key rotation lookup by kid and falls back to active key when kid omitted")
    void supportsKeyRotationByKid() {
        String activePem = Base64.getEncoder().encodeToString(primaryPair.getPublic().getEncoded());
        String prevPem = Base64.getEncoder().encodeToString(previousPair.getPublic().getEncoded());
        JwtKeyLoader loader = new JwtKeyLoader(activePem, List.of(prevPem));

        String activeKid = JwtKeyLoader.computeKeyId((RSAPublicKey) primaryPair.getPublic());
        String prevKid = JwtKeyLoader.computeKeyId((RSAPublicKey) previousPair.getPublic());

        assertThat(loader.getKeyId()).isEqualTo(activeKid);
        assertThat(loader.getVerificationKey(null)).isEqualTo(primaryPair.getPublic());
        assertThat(loader.getVerificationKey(activeKid)).isEqualTo(primaryPair.getPublic());
        assertThat(loader.getVerificationKey(prevKid)).isEqualTo(previousPair.getPublic());
        assertThat(loader.getVerificationKey("unknown-kid")).isNull();
        assertThat(loader.getAllPublicKeys()).hasSize(2);
    }

    @Test
    @DisplayName("Fails closed on blank or malformed key material")
    void failsClosedOnInvalidKeys() {
        assertThatThrownBy(() -> JwtKeyLoader.parsePublicKey(null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtKeyLoader.parsePublicKey("   "))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtKeyLoader.parsePublicKey("not-a-valid-key"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtKeyLoader.parsePrivateKey("invalid"))
                .isInstanceOf(IllegalStateException.class);
    }
}
