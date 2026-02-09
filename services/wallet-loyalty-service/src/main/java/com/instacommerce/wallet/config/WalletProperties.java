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

        public int getPointsPerRupee() { return pointsPerRupee; }
        public void setPointsPerRupee(int pointsPerRupee) { this.pointsPerRupee = pointsPerRupee; }
        public int getPointsExpiryMonths() { return pointsExpiryMonths; }
        public void setPointsExpiryMonths(int pointsExpiryMonths) { this.pointsExpiryMonths = pointsExpiryMonths; }
    }

    public static class Referral {
        private long rewardCents = 5000;

        public long getRewardCents() { return rewardCents; }
        public void setRewardCents(long rewardCents) { this.rewardCents = rewardCents; }
    }
}
