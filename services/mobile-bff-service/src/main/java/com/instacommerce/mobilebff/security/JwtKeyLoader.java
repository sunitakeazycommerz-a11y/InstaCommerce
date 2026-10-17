package com.instacommerce.mobilebff.security;

import com.instacommerce.mobilebff.config.MobileBffProperties;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class JwtKeyLoader {
    private final RSAPublicKey publicKey;

    public JwtKeyLoader(MobileBffProperties properties) {
        String key = properties.getJwt() != null ? properties.getJwt().getPublicKey() : null;
        if (key != null && !key.isBlank()) {
            this.publicKey = (RSAPublicKey) parsePublicKey(key);
        } else {
            this.publicKey = null;
        }
    }

    public RSAPublicKey getPublicKey() {
        if (publicKey == null) {
            throw new IllegalStateException("JWT public key is not configured for mobile-bff-service");
        }
        return publicKey;
    }

    public boolean hasKey() {
        return publicKey != null;
    }

    private static PublicKey parsePublicKey(String keyValue) {
        try {
            byte[] decoded = decodeKeyMaterial(keyValue);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to parse JWT public key", ex);
        }
    }

    private static byte[] decodeKeyMaterial(String keyValue) {
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
}

