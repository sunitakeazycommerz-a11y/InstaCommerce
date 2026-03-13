package com.instacommerce.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "order")
public class OrderProperties {
    private final Jwt jwt = new Jwt();
    private final Clients clients = new Clients();
    private final Checkout checkout = new Checkout();

    public Jwt getJwt() {
        return jwt;
    }

    public Clients getClients() {
        return clients;
    }

    public Checkout getCheckout() {
        return checkout;
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
        private final Service pricing = new Service();

        public Service getInventory() {
            return inventory;
        }

        public Service getPayment() {
            return payment;
        }

        public Service getCart() {
            return cart;
        }

        public Service getPricing() {
            return pricing;
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

    /**
     * @deprecated Checkout authority has moved to checkout-orchestrator-service (ADR-001).
     * This configuration exists only for rollback safety. Do not re-enable without principal approval.
     */
    @Deprecated(since = "wave-22", forRemoval = true)
    public static class Checkout {
        private boolean directSagaEnabled = false;

        public boolean isDirectSagaEnabled() {
            return directSagaEnabled;
        }

        public void setDirectSagaEnabled(boolean directSagaEnabled) {
            this.directSagaEnabled = directSagaEnabled;
        }
    }
}
