package com.instacommerce.checkout.workflow;

import com.instacommerce.checkout.dto.CheckoutRequest;
import com.instacommerce.checkout.dto.CheckoutResponse;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface CheckoutWorkflow {

    @WorkflowMethod
    CheckoutResponse checkout(CheckoutRequest request);

    @QueryMethod
    String getStatus();
}
