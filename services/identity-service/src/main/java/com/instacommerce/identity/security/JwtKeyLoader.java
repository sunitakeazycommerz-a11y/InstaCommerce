package com.instacommerce.identity.security;

import com.instacommerce.identity.config.IdentityProperties;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class JwtKeyLoader {
    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;
    private final String keyId;

    public JwtKeyLoader(IdentityProperties identityProperties) {
        String publicKeyValue = identityProperties.getJwt().getPublicKey();
        String privateKeyValue = identityProperties.getJwt().getPrivateKey();
        this.privateKey = (RSAPrivateKey) parsePrivateKey(privateKeyValue);
        this.publicKey = resolvePublicKey(publicKeyValue, this.privateKey);
        this.keyId = computeKeyId(this.publicKey);
    }

    public RSAPublicKey getPublicKey() {
        return publicKey;
    }

    public RSAPrivateKey getPrivateKey() {
        return privateKey;
    }

    public String getKeyId() {
        return keyId;
    }

    private static PublicKey parsePublicKey(String keyValue) {
        try {
            byte[] decoded = decodeKeyMaterial(keyValue, "public");
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to parse JWT public key", ex);
        }
    }

    private static PrivateKey parsePrivateKey(String keyValue) {
        try {
            byte[] decoded = decodeKeyMaterial(keyValue, "private");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to parse JWT private key", ex);
        }
    }

    private static RSAPublicKey resolvePublicKey(String publicKeyValue, RSAPrivateKey privateKey) {
        if (publicKeyValue != null && !publicKeyValue.isBlank()) {
            return (RSAPublicKey) parsePublicKey(publicKeyValue);
        }
        if (privateKey instanceof RSAPrivateCrtKey crtKey) {
            try {
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                RSAPublicKeySpec spec = new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent());
                return (RSAPublicKey) keyFactory.generatePublic(spec);
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException("Failed to derive JWT public key", ex);
            }
        }
        throw new IllegalStateException("JWT public key is not configured");
    }

    private static byte[] decodeKeyMaterial(String keyValue, String label) {
        if (keyValue == null || keyValue.isBlank()) {
            throw new IllegalStateException("JWT " + label + " key is not configured");
        }
        String normalized = keyValue.trim();
        if (normalized.contains("BEGIN")) {
            normalized = normalized
                    .replaceAll("-----BEGIN ([^-]+)-----", "")
                    .replaceAll("-----END ([^-]+)-----", "")
                    .replaceAll("\\s", "");
        } else {
            normalized = normalized.replaceAll("\\s", "");
        }
        try {
            return Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException ex) {
            return Base64.getUrlDecoder().decode(normalized);
        }
    }

    private static String computeKeyId(RSAPublicKey publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(unsignedBytes(publicKey.getModulus()));
            digest.update(unsignedBytes(publicKey.getPublicExponent()));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Unable to compute JWT key id", ex);
        }
    }

    private static byte[] unsignedBytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            return Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }
}
