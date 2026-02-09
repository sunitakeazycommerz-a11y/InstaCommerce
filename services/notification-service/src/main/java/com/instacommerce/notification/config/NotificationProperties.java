package com.instacommerce.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {
    private final Providers providers = new Providers();
    private final Identity identity = new Identity();
    private final Order order = new Order();
    private final Delivery delivery = new Delivery();
    private String dlqTopic = "notifications.dlq";

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

    public String getDlqTopic() {
        return dlqTopic;
    }

    public void setDlqTopic(String dlqTopic) {
        this.dlqTopic = dlqTopic;
    }

    public static class Providers {
        private final SendGrid sendgrid = new SendGrid();
        private final Twilio twilio = new Twilio();

        public SendGrid getSendgrid() {
            return sendgrid;
        }

        public Twilio getTwilio() {
            return twilio;
        }
    }

    public static class SendGrid {
        private String apiKey;
        private String fromEmail;

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

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
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
}
