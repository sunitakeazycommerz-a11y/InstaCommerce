package com.instacommerce.mobilebff.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "mobile-bff")
public class MobileBffProperties {

    private JwtProperties jwt = new JwtProperties();
    private UpstreamProperties upstream = new UpstreamProperties();

    public JwtProperties getJwt() {
        return jwt;
    }

    public void setJwt(JwtProperties jwt) {
        this.jwt = jwt;
    }

    public UpstreamProperties getUpstream() {
        return upstream;
    }

    public void setUpstream(UpstreamProperties upstream) {
        this.upstream = upstream;
    }

    public static class JwtProperties {
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

    public static class UpstreamProperties {
        private String catalogUrl = "http://catalog-service:8082";
        private String searchUrl = "http://search-service:8088";
        private String featureFlagUrl = "http://config-feature-flag-service:8098";
        private String cartUrl = "http://cart-service:8090";
        private String orderUrl = "http://order-service:8084";
        private String identityUrl = "http://identity-service:8081";
        private String walletUrl = "http://wallet-loyalty-service:8095";
        private String pricingUrl = "http://pricing-service:8089";
        private String inventoryUrl = "http://inventory-service:8083";
        private String fulfillmentUrl = "http://fulfillment-service:8086";
        private String routingEtaUrl = "http://routing-eta-service:8094";
        private String checkoutUrl = "http://checkout-orchestrator-service:8091";
        private int timeoutMs = 2000;

        public String getCatalogUrl() {
            return catalogUrl;
        }

        public void setCatalogUrl(String catalogUrl) {
            this.catalogUrl = catalogUrl;
        }

        public String getSearchUrl() {
            return searchUrl;
        }

        public void setSearchUrl(String searchUrl) {
            this.searchUrl = searchUrl;
        }

        public String getFeatureFlagUrl() {
            return featureFlagUrl;
        }

        public void setFeatureFlagUrl(String featureFlagUrl) {
            this.featureFlagUrl = featureFlagUrl;
        }

        public String getCartUrl() {
            return cartUrl;
        }

        public void setCartUrl(String cartUrl) {
            this.cartUrl = cartUrl;
        }

        public String getOrderUrl() {
            return orderUrl;
        }

        public void setOrderUrl(String orderUrl) {
            this.orderUrl = orderUrl;
        }

        public String getIdentityUrl() {
            return identityUrl;
        }

        public void setIdentityUrl(String identityUrl) {
            this.identityUrl = identityUrl;
        }

        public String getWalletUrl() {
            return walletUrl;
        }

        public void setWalletUrl(String walletUrl) {
            this.walletUrl = walletUrl;
        }

        public String getPricingUrl() {
            return pricingUrl;
        }

        public void setPricingUrl(String pricingUrl) {
            this.pricingUrl = pricingUrl;
        }

        public String getInventoryUrl() {
            return inventoryUrl;
        }

        public void setInventoryUrl(String inventoryUrl) {
            this.inventoryUrl = inventoryUrl;
        }

        public String getFulfillmentUrl() {
            return fulfillmentUrl;
        }

        public void setFulfillmentUrl(String fulfillmentUrl) {
            this.fulfillmentUrl = fulfillmentUrl;
        }

        public String getRoutingEtaUrl() {
            return routingEtaUrl;
        }

        public void setRoutingEtaUrl(String routingEtaUrl) {
            this.routingEtaUrl = routingEtaUrl;
        }

        public String getCheckoutUrl() {
            return checkoutUrl;
        }

        public void setCheckoutUrl(String checkoutUrl) {
            this.checkoutUrl = checkoutUrl;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}
