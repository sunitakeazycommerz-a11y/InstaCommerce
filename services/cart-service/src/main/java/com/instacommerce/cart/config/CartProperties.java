package com.instacommerce.cart.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cart")
public class CartProperties {
    private final Jwt jwt = new Jwt();
    private final Kafka kafka = new Kafka();
    private int maxItemsPerCart = 50;
    private int maxQuantityPerItem = 10;
    private long minOrderCents = 0L;
    private long maxCartValueCents = 10_000_000L;
    private List<String> serviceableStores = new ArrayList<>();

    public Jwt getJwt() {
        return jwt;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public int getMaxItemsPerCart() {
        return maxItemsPerCart;
    }

    public void setMaxItemsPerCart(int maxItemsPerCart) {
        this.maxItemsPerCart = maxItemsPerCart;
    }

    public int getMaxQuantityPerItem() {
        return maxQuantityPerItem;
    }

    public void setMaxQuantityPerItem(int maxQuantityPerItem) {
        this.maxQuantityPerItem = maxQuantityPerItem;
    }

    public long getMinOrderCents() {
        return minOrderCents;
    }

    public void setMinOrderCents(long minOrderCents) {
        this.minOrderCents = minOrderCents;
    }

    public long getMaxCartValueCents() {
        return maxCartValueCents;
    }

    public void setMaxCartValueCents(long maxCartValueCents) {
        this.maxCartValueCents = maxCartValueCents;
    }

    public List<String> getServiceableStores() {
        return serviceableStores;
    }

    public void setServiceableStores(List<String> serviceableStores) {
        this.serviceableStores = serviceableStores != null ? serviceableStores : new ArrayList<>();
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

    public static class Kafka {
        private String cartEventsTopic = "cart.events";

        public String getCartEventsTopic() {
            return cartEventsTopic;
        }

        public void setCartEventsTopic(String cartEventsTopic) {
            this.cartEventsTopic = cartEventsTopic;
        }
    }
}
