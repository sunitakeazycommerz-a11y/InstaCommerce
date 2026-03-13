package com.instacommerce.riderfleet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rider-fleet")
public class RiderFleetProperties {
    private final Jwt jwt = new Jwt();
    private final Assignment assignment = new Assignment();
    private final Recovery recovery = new Recovery();
    private final Dispatch dispatch = new Dispatch();

    public Jwt getJwt() {
        return jwt;
    }

    public Assignment getAssignment() {
        return assignment;
    }

    public Recovery getRecovery() {
        return recovery;
    }

    public Dispatch getDispatch() {
        return dispatch;
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

    public static class Assignment {
        private double defaultRadiusKm = 5.0;

        public double getDefaultRadiusKm() {
            return defaultRadiusKm;
        }

        public void setDefaultRadiusKm(double defaultRadiusKm) {
            this.defaultRadiusKm = defaultRadiusKm;
        }
    }

    public static class Recovery {
        private boolean stuckRiderEnabled = false;
        private int stuckThresholdMinutes = 60;
        private int batchSize = 50;
        private String stuckRiderCron = "0 */10 * * * *";

        public boolean isStuckRiderEnabled() {
            return stuckRiderEnabled;
        }

        public void setStuckRiderEnabled(boolean stuckRiderEnabled) {
            this.stuckRiderEnabled = stuckRiderEnabled;
        }

        public int getStuckThresholdMinutes() {
            return stuckThresholdMinutes;
        }

        public void setStuckThresholdMinutes(int stuckThresholdMinutes) {
            this.stuckThresholdMinutes = stuckThresholdMinutes;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public String getStuckRiderCron() {
            return stuckRiderCron;
        }

        public void setStuckRiderCron(String stuckRiderCron) {
            this.stuckRiderCron = stuckRiderCron;
        }
    }

    public static class Dispatch {
        private boolean optimizerEnabled = false;
        private String optimizerBaseUrl = "http://dispatch-optimizer-service:8102";

        public boolean isOptimizerEnabled() {
            return optimizerEnabled;
        }

        public void setOptimizerEnabled(boolean optimizerEnabled) {
            this.optimizerEnabled = optimizerEnabled;
        }

        public String getOptimizerBaseUrl() {
            return optimizerBaseUrl;
        }

        public void setOptimizerBaseUrl(String optimizerBaseUrl) {
            this.optimizerBaseUrl = optimizerBaseUrl;
        }
    }
}
