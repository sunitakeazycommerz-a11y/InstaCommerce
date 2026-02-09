package com.instacommerce.cart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cart")
public class CartProperties {
    private final Jwt jwt = new Jwt();
    private final Kafka kafka = new Kafka();
    private int maxItemsPerCart = 50;
    private int maxQuantityPerItem = 10;

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
        private String cartEventsTopic = "cart-events";

        public String getCartEventsTopic() {
            return cartEventsTopic;
        }

        public void setCartEventsTopic(String cartEventsTopic) {
            this.cartEventsTopic = cartEventsTopic;
        }
    }
}
