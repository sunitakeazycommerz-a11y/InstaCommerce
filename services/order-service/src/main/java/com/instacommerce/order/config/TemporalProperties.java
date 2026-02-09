package com.instacommerce.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "temporal")
public class TemporalProperties {
    private String serviceAddress = "localhost:7233";
    private String namespace = "instacommerce";
    private String taskQueue = "CHECKOUT_TASK_QUEUE";

    public String getServiceAddress() {
        return serviceAddress;
    }

    public void setServiceAddress(String serviceAddress) {
        this.serviceAddress = serviceAddress;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getTaskQueue() {
        return taskQueue;
    }

    public void setTaskQueue(String taskQueue) {
        this.taskQueue = taskQueue;
    }
}
