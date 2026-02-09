package com.instacommerce.featureflag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "feature-flag")
public class FeatureFlagProperties {

    private final Jwt jwt = new Jwt();
    private final Cache cache = new Cache();

    public Jwt getJwt() {
        return jwt;
    }

    public Cache getCache() {
        return cache;
    }

    public static class Jwt {
        private String issuer = "instacommerce-identity";
        private String publicKey;

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
    }

    public static class Cache {
        private int flagsTtlSeconds = 30;
        private int maxSize = 5000;

        public int getFlagsTtlSeconds() {
            return flagsTtlSeconds;
        }

        public void setFlagsTtlSeconds(int flagsTtlSeconds) {
            this.flagsTtlSeconds = flagsTtlSeconds;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }
    }
}
