package com.instacommerce.riderfleet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rider-fleet")
public class RiderFleetProperties {
    private final Jwt jwt = new Jwt();
    private final Assignment assignment = new Assignment();

    public Jwt getJwt() {
        return jwt;
    }

    public Assignment getAssignment() {
        return assignment;
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
}
