package com.instacommerce.routing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "routing")
public class RoutingProperties {

    private final Jwt jwt = new Jwt();
    private final Eta eta = new Eta();
    private final Breach breach = new Breach();

    public Jwt getJwt() {
        return jwt;
    }

    public Eta getEta() {
        return eta;
    }

    public Breach getBreach() {
        return breach;
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

    public static class Breach {
        private int slaThresholdMinutes = 30;
        private double breachAlertThreshold = 0.7;
        private int etaRecalcMinDeltaMinutes = 2;
        private double etaLowMultiplier = 0.8;
        private double etaHighMultiplier = 1.3;
        private boolean recalcOnLocationUpdateEnabled = true;

        public int getSlaThresholdMinutes() {
            return slaThresholdMinutes;
        }

        public void setSlaThresholdMinutes(int slaThresholdMinutes) {
            this.slaThresholdMinutes = slaThresholdMinutes;
        }

        public double getBreachAlertThreshold() {
            return breachAlertThreshold;
        }

        public void setBreachAlertThreshold(double breachAlertThreshold) {
            this.breachAlertThreshold = breachAlertThreshold;
        }

        public int getEtaRecalcMinDeltaMinutes() {
            return etaRecalcMinDeltaMinutes;
        }

        public void setEtaRecalcMinDeltaMinutes(int etaRecalcMinDeltaMinutes) {
            this.etaRecalcMinDeltaMinutes = etaRecalcMinDeltaMinutes;
        }

        public double getEtaLowMultiplier() {
            return etaLowMultiplier;
        }

        public void setEtaLowMultiplier(double etaLowMultiplier) {
            this.etaLowMultiplier = etaLowMultiplier;
        }

        public double getEtaHighMultiplier() {
            return etaHighMultiplier;
        }

        public void setEtaHighMultiplier(double etaHighMultiplier) {
            this.etaHighMultiplier = etaHighMultiplier;
        }

        public boolean isRecalcOnLocationUpdateEnabled() {
            return recalcOnLocationUpdateEnabled;
        }

        public void setRecalcOnLocationUpdateEnabled(boolean recalcOnLocationUpdateEnabled) {
            this.recalcOnLocationUpdateEnabled = recalcOnLocationUpdateEnabled;
        }
    }
}
