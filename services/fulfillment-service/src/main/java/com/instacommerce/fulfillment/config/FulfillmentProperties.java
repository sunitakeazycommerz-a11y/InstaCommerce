package com.instacommerce.fulfillment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fulfillment")
public class FulfillmentProperties {
    private final Jwt jwt = new Jwt();
    private final Clients clients = new Clients();
    private final Delivery delivery = new Delivery();

    public Jwt getJwt() {
        return jwt;
    }

    public Clients getClients() {
        return clients;
    }

    public Delivery getDelivery() {
        return delivery;
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
        private final Service order = new Service();
        private final Service payment = new Service();
        private final Service inventory = new Service();

        public Service getOrder() {
            return order;
        }

        public Service getPayment() {
            return payment;
        }

        public Service getInventory() {
            return inventory;
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

    public static class Delivery {
        private int defaultEtaMinutes = 15;

        public int getDefaultEtaMinutes() {
            return defaultEtaMinutes;
        }

        public void setDefaultEtaMinutes(int defaultEtaMinutes) {
            this.defaultEtaMinutes = defaultEtaMinutes;
        }
    }
}
