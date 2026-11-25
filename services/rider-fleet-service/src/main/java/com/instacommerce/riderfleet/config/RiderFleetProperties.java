package com.instacommerce.riderfleet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rider-fleet")
public class RiderFleetProperties {
    private final Jwt jwt = new Jwt();
    private final Assignment assignment = new Assignment();
    private final Recovery recovery = new Recovery();
    private final Dispatch dispatch = new Dispatch();
    private final Earnings earnings = new Earnings();

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

    public Earnings getEarnings() {
        return earnings;
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
        private int maxConsecutiveDeliveries = 8;
        private int fatigueResetMinutes = 45;
        private boolean enforceShiftValidation = false;

        public double getDefaultRadiusKm() {
            return defaultRadiusKm;
        }

        public void setDefaultRadiusKm(double defaultRadiusKm) {
            this.defaultRadiusKm = defaultRadiusKm;
        }

        public int getMaxConsecutiveDeliveries() {
            return maxConsecutiveDeliveries;
        }

        public void setMaxConsecutiveDeliveries(int maxConsecutiveDeliveries) {
            this.maxConsecutiveDeliveries = maxConsecutiveDeliveries;
        }

        public int getFatigueResetMinutes() {
            return fatigueResetMinutes;
        }

        public void setFatigueResetMinutes(int fatigueResetMinutes) {
            this.fatigueResetMinutes = fatigueResetMinutes;
        }

        public boolean isEnforceShiftValidation() {
            return enforceShiftValidation;
        }

        public void setEnforceShiftValidation(boolean enforceShiftValidation) {
            this.enforceShiftValidation = enforceShiftValidation;
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

    public static class Earnings {
        private long baseFeeCents = 2500L;
        private long perKmFeeCents = 600L;
        private long minDeliveryFeeCents = 2500L;
        private long peakHourBonusCents = 500L;
        private int streakThreshold = 5;
        private long streakBonusCents = 400L;
        private double highRatingThreshold = 4.75;
        private long highRatingBonusCents = 300L;
        private long weekendBonusCents = 250L;
        private int heavyOrderWeightGrams = 10_000;
        private long heavyOrderBonusCents = 300L;
        private String timezone = "Asia/Kolkata";

        public long getBaseFeeCents() {
            return baseFeeCents;
        }

        public void setBaseFeeCents(long baseFeeCents) {
            this.baseFeeCents = baseFeeCents;
        }

        public long getPerKmFeeCents() {
            return perKmFeeCents;
        }

        public void setPerKmFeeCents(long perKmFeeCents) {
            this.perKmFeeCents = perKmFeeCents;
        }

        public long getMinDeliveryFeeCents() {
            return minDeliveryFeeCents;
        }

        public void setMinDeliveryFeeCents(long minDeliveryFeeCents) {
            this.minDeliveryFeeCents = minDeliveryFeeCents;
        }

        public long getPeakHourBonusCents() {
            return peakHourBonusCents;
        }

        public void setPeakHourBonusCents(long peakHourBonusCents) {
            this.peakHourBonusCents = peakHourBonusCents;
        }

        public int getStreakThreshold() {
            return streakThreshold;
        }

        public void setStreakThreshold(int streakThreshold) {
            this.streakThreshold = streakThreshold;
        }

        public long getStreakBonusCents() {
            return streakBonusCents;
        }

        public void setStreakBonusCents(long streakBonusCents) {
            this.streakBonusCents = streakBonusCents;
        }

        public double getHighRatingThreshold() {
            return highRatingThreshold;
        }

        public void setHighRatingThreshold(double highRatingThreshold) {
            this.highRatingThreshold = highRatingThreshold;
        }

        public long getHighRatingBonusCents() {
            return highRatingBonusCents;
        }

        public void setHighRatingBonusCents(long highRatingBonusCents) {
            this.highRatingBonusCents = highRatingBonusCents;
        }

        public long getWeekendBonusCents() {
            return weekendBonusCents;
        }

        public void setWeekendBonusCents(long weekendBonusCents) {
            this.weekendBonusCents = weekendBonusCents;
        }

        public int getHeavyOrderWeightGrams() {
            return heavyOrderWeightGrams;
        }

        public void setHeavyOrderWeightGrams(int heavyOrderWeightGrams) {
            this.heavyOrderWeightGrams = heavyOrderWeightGrams;
        }

        public long getHeavyOrderBonusCents() {
            return heavyOrderBonusCents;
        }

        public void setHeavyOrderBonusCents(long heavyOrderBonusCents) {
            this.heavyOrderBonusCents = heavyOrderBonusCents;
        }

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }
    }
}
