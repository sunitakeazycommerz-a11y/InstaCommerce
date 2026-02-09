package com.instacommerce.warehouse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "warehouse")
public class WarehouseProperties {

    private final Jwt jwt = new Jwt();
    private final NearestStore nearestStore = new NearestStore();

    public Jwt getJwt() {
        return jwt;
    }

    public NearestStore getNearestStore() {
        return nearestStore;
    }

    public static class Jwt {
        private String issuer = "instacommerce-identity";
        private String publicKey;

        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }

        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    }

    public static class NearestStore {
        private double defaultRadiusKm = 10.0;
        private int maxResults = 5;

        public double getDefaultRadiusKm() { return defaultRadiusKm; }
        public void setDefaultRadiusKm(double defaultRadiusKm) { this.defaultRadiusKm = defaultRadiusKm; }

        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
    }
}
