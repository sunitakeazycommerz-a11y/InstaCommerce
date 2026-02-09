package com.instacommerce.pricing.security;

import com.instacommerce.pricing.config.PricingProperties;
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

    public JwtKeyLoader(PricingProperties pricingProperties) {
        this.publicKey = (RSAPublicKey) parsePublicKey(pricingProperties.getJwt().getPublicKey());
    }

    public RSAPublicKey getPublicKey() {
        return publicKey;
    }

    private static PublicKey parsePublicKey(String keyValue) {
        if (keyValue == null || keyValue.isBlank()) {
            throw new IllegalStateException("JWT public key is not configured");
        }
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
