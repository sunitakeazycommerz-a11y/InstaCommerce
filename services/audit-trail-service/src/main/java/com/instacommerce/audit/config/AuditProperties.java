package com.instacommerce.audit.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "audit")
public class AuditProperties {

    private final Jwt jwt = new Jwt();
    private final Partition partition = new Partition();
    private String dlqTopic = "audit.dlq";

    public Jwt getJwt() {
        return jwt;
    }

    public Partition getPartition() {
        return partition;
    }

    public String getDlqTopic() {
        return dlqTopic;
    }

    public void setDlqTopic(String dlqTopic) {
        this.dlqTopic = dlqTopic;
    }

    public static class Jwt {
        @NotBlank
        private String issuer;
        @NotBlank
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

    public static class Partition {
        private int retentionDays = 90;
        private int futureMonths = 3;

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }

        public int getFutureMonths() {
            return futureMonths;
        }

        public void setFutureMonths(int futureMonths) {
            this.futureMonths = futureMonths;
        }
    }
}
