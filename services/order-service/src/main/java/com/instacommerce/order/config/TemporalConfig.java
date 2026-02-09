package com.instacommerce.order.config;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TemporalConfig {
    @Bean
    public WorkflowServiceStubs workflowServiceStubs(TemporalProperties properties) {
        WorkflowServiceStubsOptions options = WorkflowServiceStubsOptions.newBuilder()
            .setTarget(properties.getServiceAddress())
            .build();
        return WorkflowServiceStubs.newInstance(options);
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs serviceStubs, TemporalProperties properties) {
        WorkflowClientOptions options = WorkflowClientOptions.newBuilder()
            .setNamespace(properties.getNamespace())
            .build();
        return WorkflowClient.newInstance(serviceStubs, options);
    }
}
