package com.instacommerce.fulfillment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fulfillment")
public class FulfillmentProperties {
    private final Jwt jwt = new Jwt();
    private final Clients clients = new Clients();
    private final Delivery delivery = new Delivery();
    private final Choreography choreography = new Choreography();
    private final Dispatch dispatch = new Dispatch();

    public Jwt getJwt() {
        return jwt;
    }

    public Clients getClients() {
        return clients;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public Choreography getChoreography() {
        return choreography;
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

    public static class Clients {
        private final Service order = new Service();
        private final Service payment = new Service();
        private final Service inventory = new Service();
        private final Service warehouse = new Service();

        public Service getOrder() {
            return order;
        }

        public Service getPayment() {
            return payment;
        }

        public Service getInventory() {
            return inventory;
        }

        public Service getWarehouse() {
            return warehouse;
        }
    }

    public static class Service {
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

    public static class Choreography {
        private boolean orderStatusCallbackEnabled = true;

        public boolean isOrderStatusCallbackEnabled() {
            return orderStatusCallbackEnabled;
        }

        public void setOrderStatusCallbackEnabled(boolean orderStatusCallbackEnabled) {
            this.orderStatusCallbackEnabled = orderStatusCallbackEnabled;
        }
    }

    public static class Dispatch {
        private boolean inlineAssignmentEnabled = false;

        public boolean isInlineAssignmentEnabled() {
            return inlineAssignmentEnabled;
        }

        public void setInlineAssignmentEnabled(boolean inlineAssignmentEnabled) {
            this.inlineAssignmentEnabled = inlineAssignmentEnabled;
        }
    }
}
