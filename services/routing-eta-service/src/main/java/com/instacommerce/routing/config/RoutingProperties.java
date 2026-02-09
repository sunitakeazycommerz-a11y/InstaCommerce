package com.instacommerce.routing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "routing")
public class RoutingProperties {

    private final Jwt jwt = new Jwt();
    private final Eta eta = new Eta();

    public Jwt getJwt() {
        return jwt;
    }

    public Eta getEta() {
        return eta;
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

    public static class Eta {
        private int averageSpeedKmh = 25;
        private int preparationTimeMinutes = 3;
        private double roadDistanceMultiplier = 1.4;
        private double peakSpeedMultiplier = 0.6;
        private double nightSpeedMultiplier = 1.2;

        public int getAverageSpeedKmh() {
            return averageSpeedKmh;
        }

        public void setAverageSpeedKmh(int averageSpeedKmh) {
            this.averageSpeedKmh = averageSpeedKmh;
        }

        public int getPreparationTimeMinutes() {
            return preparationTimeMinutes;
        }

        public void setPreparationTimeMinutes(int preparationTimeMinutes) {
            this.preparationTimeMinutes = preparationTimeMinutes;
        }

        public double getRoadDistanceMultiplier() {
            return roadDistanceMultiplier;
        }

        public void setRoadDistanceMultiplier(double roadDistanceMultiplier) {
            this.roadDistanceMultiplier = roadDistanceMultiplier;
        }

        public double getPeakSpeedMultiplier() {
            return peakSpeedMultiplier;
        }

        public void setPeakSpeedMultiplier(double peakSpeedMultiplier) {
            this.peakSpeedMultiplier = peakSpeedMultiplier;
        }

        public double getNightSpeedMultiplier() {
            return nightSpeedMultiplier;
        }

        public void setNightSpeedMultiplier(double nightSpeedMultiplier) {
            this.nightSpeedMultiplier = nightSpeedMultiplier;
        }
    }
}
