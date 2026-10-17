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
    public record KeyEntry(String kid, RSAPublicKey publicKey) {}

    private final RSAPublicKey activePublicKey;
    private final RSAPrivateKey activePrivateKey;
    private final String activeKeyId;
    private final java.util.Map<String, RSAPublicKey> verificationKeys;
    private final java.util.List<KeyEntry> allPublicKeys;

    public JwtKeyLoader(IdentityProperties identityProperties) {
        String publicKeyValue = identityProperties.getJwt().getPublicKey();
        String privateKeyValue = identityProperties.getJwt().getPrivateKey();
        this.activePrivateKey = (RSAPrivateKey) parsePrivateKey(privateKeyValue);
        this.activePublicKey = resolvePublicKey(publicKeyValue, this.activePrivateKey);
        this.activeKeyId = computeKeyId(this.activePublicKey);

        java.util.Map<String, RSAPublicKey> verKeys = new java.util.LinkedHashMap<>();
        java.util.List<KeyEntry> allKeys = new java.util.ArrayList<>();

        // Active key is primary for signing and verification
        verKeys.put(this.activeKeyId, this.activePublicKey);
        allKeys.add(new KeyEntry(this.activeKeyId, this.activePublicKey));

        // Prior keys in rotation grace period
        java.util.List<String> previousKeys = identityProperties.getJwt().getPreviousPublicKeys();
        if (previousKeys != null) {
            for (String prevKeyPem : previousKeys) {
                if (prevKeyPem != null && !prevKeyPem.isBlank()) {
                    RSAPublicKey prevPub = (RSAPublicKey) parsePublicKey(prevKeyPem);
                    String prevKid = computeKeyId(prevPub);
                    if (!verKeys.containsKey(prevKid)) {
                        verKeys.put(prevKid, prevPub);
                        allKeys.add(new KeyEntry(prevKid, prevPub));
                    }
                }
            }
        }

        this.verificationKeys = java.util.Collections.unmodifiableMap(verKeys);
        this.allPublicKeys = java.util.Collections.unmodifiableList(allKeys);
    }

    public RSAPublicKey getPublicKey() {
        return activePublicKey;
    }

    public RSAPrivateKey getPrivateKey() {
        return activePrivateKey;
    }

    public String getKeyId() {
        return activeKeyId;
    }

    public RSAPublicKey getVerificationKey(String kid) {
        if (kid == null) {
            return activePublicKey;
        }
        return verificationKeys.get(kid);
    }

    public java.util.List<KeyEntry> getAllPublicKeys() {
        return allPublicKeys;
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
