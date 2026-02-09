package com.instacommerce.identity.service;

import com.instacommerce.identity.config.IdentityProperties;
import com.instacommerce.identity.domain.model.User;
import com.instacommerce.identity.security.JwtService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    private final JwtService jwtService;
    private final IdentityProperties identityProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenService(JwtService jwtService, IdentityProperties identityProperties) {
        this.jwtService = jwtService;
        this.identityProperties = identityProperties;
    }

    public String generateAccessToken(User user) {
        return jwtService.generateAccessToken(user);
    }

    public String generateRefreshToken() {
        byte[] bytes = new byte[64];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hashRefreshToken(String refreshToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash refresh token", ex);
        }
    }

    public Instant refreshTokenExpiresAt() {
        return Instant.now().plusSeconds(identityProperties.getToken().getRefreshTtlSeconds());
    }

    public long accessTokenTtlSeconds() {
        return identityProperties.getToken().getAccessTtlSeconds();
    }

    public int maxRefreshTokens() {
        return identityProperties.getToken().getMaxRefreshTokens();
    }
}
