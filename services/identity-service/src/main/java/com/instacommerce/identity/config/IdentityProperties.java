package com.instacommerce.identity.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "identity")
public class IdentityProperties {
    private final Cors cors = new Cors();
    private final Token token = new Token();
    private final Jwt jwt = new Jwt();

    public Cors getCors() {
        return cors;
    }

    public Token getToken() {
        return token;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public static class Cors {
        private String allowedOrigins = "http://localhost:3000,https://*.instacommerce.dev";

        public String getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(String allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    public static class Token {
        private long accessTtlSeconds = 900;
        private long refreshTtlSeconds = 604800;
        private int maxRefreshTokens = 5;

        public long getAccessTtlSeconds() {
            return accessTtlSeconds;
        }

        public void setAccessTtlSeconds(long accessTtlSeconds) {
            this.accessTtlSeconds = accessTtlSeconds;
        }

        public long getRefreshTtlSeconds() {
            return refreshTtlSeconds;
        }

        public void setRefreshTtlSeconds(long refreshTtlSeconds) {
            this.refreshTtlSeconds = refreshTtlSeconds;
        }

        public int getMaxRefreshTokens() {
            return maxRefreshTokens;
        }

        public void setMaxRefreshTokens(int maxRefreshTokens) {
            this.maxRefreshTokens = maxRefreshTokens;
        }
    }

    public static class Jwt {
        private String issuer = "instacommerce-identity";

        @NotBlank
        private String publicKey;

        @NotBlank
        private String privateKey;

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }

        public String getPrivateKey() {
            return privateKey;
        }

        public void setPrivateKey(String privateKey) {
            this.privateKey = privateKey;
        }
    }
}
