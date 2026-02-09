package com.instacommerce.order.workflow;

import com.instacommerce.order.dto.request.CheckoutRequest;
import com.instacommerce.order.workflow.model.CheckoutResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface CheckoutWorkflow {
    @WorkflowMethod
    CheckoutResult execute(CheckoutRequest request);

    @QueryMethod
    String getStatus();
}
