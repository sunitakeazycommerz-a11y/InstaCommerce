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
        private double monsoonDefaultMultiplier = 1.35;
        private double minSpeedKmh = 5.0;
        private int batchStopDwellMinutes = 3;
        private int morningPeakStartHour = 7;
        private int morningPeakEndHour = 10;
        private int eveningPeakStartHour = 17;
        private int eveningPeakEndHour = 20;
        private int nightStartHour = 22;
        private int nightEndHour = 5;
        private String timezone = "Asia/Kolkata";

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }

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

        public double getMonsoonDefaultMultiplier() {
            return monsoonDefaultMultiplier;
        }

        public void setMonsoonDefaultMultiplier(double monsoonDefaultMultiplier) {
            this.monsoonDefaultMultiplier = monsoonDefaultMultiplier;
        }

        public double getMinSpeedKmh() {
            return minSpeedKmh;
        }

        public void setMinSpeedKmh(double minSpeedKmh) {
            this.minSpeedKmh = minSpeedKmh;
        }

        public int getBatchStopDwellMinutes() {
            return batchStopDwellMinutes;
        }

        public void setBatchStopDwellMinutes(int batchStopDwellMinutes) {
            this.batchStopDwellMinutes = batchStopDwellMinutes;
        }

        public int getMorningPeakStartHour() {
            return morningPeakStartHour;
        }

        public void setMorningPeakStartHour(int morningPeakStartHour) {
            this.morningPeakStartHour = morningPeakStartHour;
        }

        public int getMorningPeakEndHour() {
            return morningPeakEndHour;
        }

        public void setMorningPeakEndHour(int morningPeakEndHour) {
            this.morningPeakEndHour = morningPeakEndHour;
        }

        public int getEveningPeakStartHour() {
            return eveningPeakStartHour;
        }

        public void setEveningPeakStartHour(int eveningPeakStartHour) {
            this.eveningPeakStartHour = eveningPeakStartHour;
        }

        public int getEveningPeakEndHour() {
            return eveningPeakEndHour;
        }

        public void setEveningPeakEndHour(int eveningPeakEndHour) {
            this.eveningPeakEndHour = eveningPeakEndHour;
        }

        public int getNightStartHour() {
            return nightStartHour;
        }

        public void setNightStartHour(int nightStartHour) {
            this.nightStartHour = nightStartHour;
        }

        public int getNightEndHour() {
            return nightEndHour;
        }

        public void setNightEndHour(int nightEndHour) {
            this.nightEndHour = nightEndHour;
        }
    }

    public static class Breach {
        private int slaThresholdMinutes = 30;
        private double breachAlertThreshold = 0.7;
        private int etaRecalcMinDeltaMinutes = 2;
        private double etaLowMultiplier = 0.8;
        private double etaHighMultiplier = 1.3;
        private boolean recalcOnLocationUpdateEnabled = true;
        private int apologyCreditCents = 5000;
        private String apologyCreditCurrency = "INR";

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

        public int getApologyCreditCents() {
            return apologyCreditCents;
        }

        public void setApologyCreditCents(int apologyCreditCents) {
            this.apologyCreditCents = apologyCreditCents;
        }

        public String getApologyCreditCurrency() {
            return apologyCreditCurrency;
        }

        public void setApologyCreditCurrency(String apologyCreditCurrency) {
            this.apologyCreditCurrency = apologyCreditCurrency;
        }
    }
}
