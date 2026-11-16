package com.instacommerce.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {
    private final Jwt jwt = new Jwt();
    private final Providers providers = new Providers();
    private final Identity identity = new Identity();
    private final Order order = new Order();
    private final Delivery delivery = new Delivery();
    private final Retry retry = new Retry();
    private String dltTopic = "notifications.DLT";

    public Jwt getJwt() {
        return jwt;
    }

    public Providers getProviders() {
        return providers;
    }

    public Identity getIdentity() {
        return identity;
    }

    public Order getOrder() {
        return order;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public Retry getRetry() {
        return retry;
    }

    public String getDltTopic() {
        return dltTopic;
    }

    public void setDltTopic(String dltTopic) {
        this.dltTopic = dltTopic;
    }

    public static class Providers {
        private final SendGrid sendgrid = new SendGrid();
        private final Twilio twilio = new Twilio();
        private final Fcm fcm = new Fcm();

        public SendGrid getSendgrid() {
            return sendgrid;
        }

        public Twilio getTwilio() {
            return twilio;
        }

        public Fcm getFcm() {
            return fcm;
        }
    }

    public static class Fcm {
        private String projectId;
        private String accessToken;
        private String endpointUrl;

        public String getProjectId() {
            return projectId;
        }

        public void setProjectId(String projectId) {
            this.projectId = projectId;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public String getEndpointUrl() {
            return endpointUrl;
        }

        public void setEndpointUrl(String endpointUrl) {
            this.endpointUrl = endpointUrl;
        }
    }

    public static class SendGrid {
        private String apiKey;
        private String fromEmail;
        private String listUnsubscribeUrl;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getFromEmail() {
            return fromEmail;
        }

        public void setFromEmail(String fromEmail) {
            this.fromEmail = fromEmail;
        }

        public String getListUnsubscribeUrl() {
            return listUnsubscribeUrl;
        }

        public void setListUnsubscribeUrl(String listUnsubscribeUrl) {
            this.listUnsubscribeUrl = listUnsubscribeUrl;
        }
    }

    public static class Twilio {
        private String accountSid;
        private String authToken;
        private String fromNumber;

        public String getAccountSid() {
            return accountSid;
        }

        public void setAccountSid(String accountSid) {
            this.accountSid = accountSid;
        }

        public String getAuthToken() {
            return authToken;
        }

        public void setAuthToken(String authToken) {
            this.authToken = authToken;
        }

        public String getFromNumber() {
            return fromNumber;
        }

        public void setFromNumber(String fromNumber) {
            this.fromNumber = fromNumber;
        }
    }

    public static class Identity {
        private String baseUrl;
        private Duration preferenceCacheTtl = Duration.ofMinutes(5);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getPreferenceCacheTtl() {
            return preferenceCacheTtl;
        }

        public void setPreferenceCacheTtl(Duration preferenceCacheTtl) {
            this.preferenceCacheTtl = preferenceCacheTtl;
        }
    }

    public static class Order {
        private String baseUrl;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class Delivery {
        private int defaultEtaMinutes = 15;

        public int getDefaultEtaMinutes() {
            return defaultEtaMinutes;
        }

        public void setDefaultEtaMinutes(int defaultEtaMinutes) {
            this.defaultEtaMinutes = defaultEtaMinutes;
        }
    }

    public static class Retry {
        private int batchSize = 100;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
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
}
