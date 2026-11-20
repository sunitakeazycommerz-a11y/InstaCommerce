package com.instacommerce.wallet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wallet")
public class WalletProperties {
    private final Jwt jwt = new Jwt();
    private final Loyalty loyalty = new Loyalty();
    private final Referral referral = new Referral();

    public Jwt getJwt() { return jwt; }
    public Loyalty getLoyalty() { return loyalty; }
    public Referral getReferral() { return referral; }

    public static class Jwt {
        private String issuer = "instacommerce-identity";
        private String publicKey;

        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    }

    public static class Loyalty {
        private int pointsPerRupee = 1;
        private int pointsExpiryMonths = 12;
        private int cashbackRateBps = 200;
        private long maxCashbackPerOrderCents = 50_000L;
        private long monthlyCashbackCapCents = 200_000L;
        private int maxPointsEarnPerOrder = 100_000;
        private int maxReferralRedemptionsPerReferrer = 50;

        public int getPointsPerRupee() { return pointsPerRupee; }
        public void setPointsPerRupee(int pointsPerRupee) { this.pointsPerRupee = pointsPerRupee; }
        public int getPointsExpiryMonths() { return pointsExpiryMonths; }
        public void setPointsExpiryMonths(int pointsExpiryMonths) { this.pointsExpiryMonths = pointsExpiryMonths; }
        public int getCashbackRateBps() { return cashbackRateBps; }
        public void setCashbackRateBps(int cashbackRateBps) { this.cashbackRateBps = cashbackRateBps; }
        public long getMaxCashbackPerOrderCents() { return maxCashbackPerOrderCents; }
        public void setMaxCashbackPerOrderCents(long maxCashbackPerOrderCents) {
            this.maxCashbackPerOrderCents = maxCashbackPerOrderCents;
        }
        public long getMonthlyCashbackCapCents() { return monthlyCashbackCapCents; }
        public void setMonthlyCashbackCapCents(long monthlyCashbackCapCents) {
            this.monthlyCashbackCapCents = monthlyCashbackCapCents;
        }
        public int getMaxPointsEarnPerOrder() { return maxPointsEarnPerOrder; }
        public void setMaxPointsEarnPerOrder(int maxPointsEarnPerOrder) {
            this.maxPointsEarnPerOrder = maxPointsEarnPerOrder;
        }
        public int getMaxReferralRedemptionsPerReferrer() { return maxReferralRedemptionsPerReferrer; }
        public void setMaxReferralRedemptionsPerReferrer(int maxReferralRedemptionsPerReferrer) {
            this.maxReferralRedemptionsPerReferrer = maxReferralRedemptionsPerReferrer;
        }
    }

    public static class Referral {
        private long rewardCents = 5000;

        public long getRewardCents() { return rewardCents; }
        public void setRewardCents(long rewardCents) { this.rewardCents = rewardCents; }
    }
}
