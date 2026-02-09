package com.instacommerce.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "order")
public class OrderProperties {
    private final Jwt jwt = new Jwt();
    private final Clients clients = new Clients();

    public Jwt getJwt() {
        return jwt;
    }

    public Clients getClients() {
        return clients;
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

    public static class Clients {
        private final Service inventory = new Service();
        private final Service payment = new Service();
        private final Service cart = new Service();

        public Service getInventory() {
            return inventory;
        }

        public Service getPayment() {
            return payment;
        }

        public Service getCart() {
            return cart;
        }
    }

    public static class Service {
        private String baseUrl;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
