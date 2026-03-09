package com.instacommerce.order.workflow.config;

import com.instacommerce.order.config.TemporalProperties;
import com.instacommerce.order.workflow.CheckoutWorkflowImpl;
import com.instacommerce.order.workflow.activities.CartActivitiesImpl;
import com.instacommerce.order.workflow.activities.InventoryActivitiesImpl;
import com.instacommerce.order.workflow.activities.OrderActivitiesImpl;
import com.instacommerce.order.workflow.activities.PaymentActivitiesImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(
    prefix = "order.checkout",
    name = "direct-saga-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class WorkerRegistration {
    private final WorkflowClient workflowClient;
    private final TemporalProperties temporalProperties;
    private final InventoryActivitiesImpl inventoryActivities;
    private final PaymentActivitiesImpl paymentActivities;
    private final OrderActivitiesImpl orderActivities;
    private final CartActivitiesImpl cartActivities;

    public WorkerRegistration(WorkflowClient workflowClient,
                              TemporalProperties temporalProperties,
                              InventoryActivitiesImpl inventoryActivities,
                              PaymentActivitiesImpl paymentActivities,
                              OrderActivitiesImpl orderActivities,
                              CartActivitiesImpl cartActivities) {
        this.workflowClient = workflowClient;
        this.temporalProperties = temporalProperties;
        this.inventoryActivities = inventoryActivities;
        this.paymentActivities = paymentActivities;
        this.orderActivities = orderActivities;
        this.cartActivities = cartActivities;
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public WorkerFactory workerFactory() {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        Worker worker = factory.newWorker(temporalProperties.getTaskQueue());
        worker.registerWorkflowImplementationTypes(CheckoutWorkflowImpl.class);
        worker.registerActivitiesImplementations(
            inventoryActivities,
            paymentActivities,
            orderActivities,
            cartActivities
        );
        return factory;
    }
}
