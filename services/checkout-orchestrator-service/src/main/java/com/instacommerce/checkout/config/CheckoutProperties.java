package com.instacommerce.checkout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "checkout")
public class CheckoutProperties {
    private final Jwt jwt = new Jwt();
    private final Clients clients = new Clients();

    public Jwt getJwt() { return jwt; }
    public Clients getClients() { return clients; }

    public static class Jwt {
        private String issuer = "instacommerce-identity";
        private String publicKey;

        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    }

    public static class Clients {
        private final ServiceClient cart = new ServiceClient();
        private final ServiceClient pricing = new ServiceClient();
        private final ServiceClient inventory = new ServiceClient();
        private final ServiceClient payment = new ServiceClient();
        private final ServiceClient order = new ServiceClient();

        public ServiceClient getCart() { return cart; }
        public ServiceClient getPricing() { return pricing; }
        public ServiceClient getInventory() { return inventory; }
        public ServiceClient getPayment() { return payment; }
        public ServiceClient getOrder() { return order; }
    }

    public static class ServiceClient {
        private String baseUrl;
        private int connectTimeout = 5000;
        private int readTimeout = 10000;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public int getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(int connectTimeout) { this.connectTimeout = connectTimeout; }
        public int getReadTimeout() { return readTimeout; }
        public void setReadTimeout(int readTimeout) { this.readTimeout = readTimeout; }
    }
}
