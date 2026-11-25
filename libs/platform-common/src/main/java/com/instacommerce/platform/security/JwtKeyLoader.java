package com.instacommerce.platform.security;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared RSA JWT key parser and rotation keyring loader supporting X.509 public keys,
 * PKCS#8 private keys, PEM headers, single-line/URL-safe Base64, and key-id ({@code kid}) lookup.
 */
public class JwtKeyLoader {
    public record KeyEntry(String kid, RSAPublicKey publicKey) {}

    private final RSAPublicKey activePublicKey;
    private final String activeKeyId;
    private final Map<String, RSAPublicKey> verificationKeys;
    private final List<KeyEntry> allPublicKeys;

    public JwtKeyLoader(String publicKeyValue) {
        this(publicKeyValue, Collections.emptyList());
    }

    public JwtKeyLoader(String publicKeyValue, Collection<String> previousPublicKeys) {
        this.activePublicKey = parsePublicKey(publicKeyValue);
        this.activeKeyId = computeKeyId(this.activePublicKey);

        Map<String, RSAPublicKey> verKeys = new LinkedHashMap<>();
        List<KeyEntry> allKeys = new ArrayList<>();
        verKeys.put(this.activeKeyId, this.activePublicKey);
        allKeys.add(new KeyEntry(this.activeKeyId, this.activePublicKey));

        if (previousPublicKeys != null) {
            for (String prevKeyPem : previousPublicKeys) {
                if (prevKeyPem == null || prevKeyPem.isBlank()) {
                    continue;
                }
                RSAPublicKey prevPub = parsePublicKey(prevKeyPem);
                String prevKid = computeKeyId(prevPub);
                if (!verKeys.containsKey(prevKid)) {
                    verKeys.put(prevKid, prevPub);
                    allKeys.add(new KeyEntry(prevKid, prevPub));
                }
            }
        }

        this.verificationKeys = Collections.unmodifiableMap(verKeys);
        this.allPublicKeys = Collections.unmodifiableList(allKeys);
    }

    public RSAPublicKey getPublicKey() {
        return activePublicKey;
    }

    public String getKeyId() {
        return activeKeyId;
    }

    public RSAPublicKey getVerificationKey(String kid) {
        if (kid == null || kid.isBlank()) {
            return activePublicKey;
        }
        return verificationKeys.get(kid);
    }

    public List<KeyEntry> getAllPublicKeys() {
        return allPublicKeys;
    }

    public static RSAPublicKey parsePublicKey(String keyValue) {
        try {
            byte[] decoded = decodeKeyMaterial(keyValue, "public");
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Failed to parse JWT public key", ex);
        }
    }

    public static RSAPrivateKey parsePrivateKey(String keyValue) {
        try {
            byte[] decoded = decodeKeyMaterial(keyValue, "private");
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Failed to parse JWT private key", ex);
        }
    }

    public static RSAPublicKey resolvePublicKey(String publicKeyValue, RSAPrivateKey privateKey) {
        if (publicKeyValue != null && !publicKeyValue.isBlank()) {
            return parsePublicKey(publicKeyValue);
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

    public static String computeKeyId(RSAPublicKey publicKey) {
        if (publicKey == null) {
            throw new IllegalArgumentException("Public key must not be null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(unsignedBytes(publicKey.getModulus()));
            digest.update(unsignedBytes(publicKey.getPublicExponent()));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Unable to compute JWT key id", ex);
        }
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

    private static byte[] unsignedBytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            return Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }
}
