package com.instacommerce.checkout.config;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import com.instacommerce.checkout.workflow.CheckoutWorkflowImpl;
import com.instacommerce.checkout.workflow.activity.CartActivityImpl;
import com.instacommerce.checkout.workflow.activity.CouponActivityImpl;
import com.instacommerce.checkout.workflow.activity.PricingActivityImpl;
import com.instacommerce.checkout.workflow.activity.InventoryActivityImpl;
import com.instacommerce.checkout.workflow.activity.PaymentActivityImpl;
import com.instacommerce.checkout.workflow.activity.OrderActivityImpl;
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

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public WorkerFactory workerFactory(WorkflowClient workflowClient,
                                       TemporalProperties temporalProperties,
                                       CartActivityImpl cartActivity,
                                       PricingActivityImpl pricingActivity,
                                       InventoryActivityImpl inventoryActivity,
                                       PaymentActivityImpl paymentActivity,
                                       OrderActivityImpl orderActivity,
                                       CouponActivityImpl couponActivity) {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        Worker worker = factory.newWorker(temporalProperties.getTaskQueue());
        worker.registerWorkflowImplementationTypes(CheckoutWorkflowImpl.class);
        worker.registerActivitiesImplementations(
            cartActivity,
            pricingActivity,
            inventoryActivity,
            paymentActivity,
            orderActivity,
            couponActivity
        );
        return factory;
    }
}
