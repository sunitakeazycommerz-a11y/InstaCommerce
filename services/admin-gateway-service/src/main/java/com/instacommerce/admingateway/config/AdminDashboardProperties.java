package com.instacommerce.admingateway.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "admin-gateway.dashboard")
public class AdminDashboardProperties {

    private long timeoutMs = 2000L;
    private long perServiceTimeoutMs = 1000L;
    private List<ServiceTarget> services = new ArrayList<>();

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public long getPerServiceTimeoutMs() {
        return perServiceTimeoutMs;
    }

    public void setPerServiceTimeoutMs(long perServiceTimeoutMs) {
        this.perServiceTimeoutMs = perServiceTimeoutMs;
    }

    public List<ServiceTarget> getServices() {
        return services;
    }

    public void setServices(List<ServiceTarget> services) {
        this.services = services != null ? services : new ArrayList<>();
    }

    public static class ServiceTarget {

        private String name;
        private String baseUrl;
        private String healthPath = "/actuator/health";
        private String metricsPath = "/actuator/metrics/http.server.requests";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getHealthPath() {
            return healthPath;
        }

        public void setHealthPath(String healthPath) {
            this.healthPath = healthPath;
        }

        public String getMetricsPath() {
            return metricsPath;
        }

        public void setMetricsPath(String metricsPath) {
            this.metricsPath = metricsPath;
        }
    }
}
