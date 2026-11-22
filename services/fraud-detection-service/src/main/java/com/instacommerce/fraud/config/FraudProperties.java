package com.instacommerce.fraud.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fraud")
public class FraudProperties {

    private final Jwt jwt = new Jwt();
    private final Cors cors = new Cors();
    private final Order order = new Order();

    public Jwt getJwt() {
        return jwt;
    }

    public Cors getCors() {
        return cors;
    }

    public Order getOrder() {
        return order;
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

    public static class Cors {
        private String allowedOrigins = "http://localhost:3000,https://*.instacommerce.dev";

        public String getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(String allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    public static class Order {
        private String baseUrl = "http://localhost:8084";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
