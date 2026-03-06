package com.instacommerce.order.controller;

import com.instacommerce.order.config.TemporalProperties;
import com.instacommerce.order.dto.request.CheckoutRequest;
import com.instacommerce.order.dto.response.CheckoutResponse;
import com.instacommerce.order.exception.DuplicateCheckoutException;
import com.instacommerce.order.service.RateLimitService;
import com.instacommerce.order.workflow.CheckoutWorkflow;
import com.instacommerce.order.workflow.model.CheckoutResult;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.WorkflowIdReusePolicy;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/checkout")
public class CheckoutController {
    // TODO: Convert to async (non-blocking) checkout. Currently blocks the HTTP thread
    // for the entire Temporal workflow execution. Future: return 202 Accepted with a
    // polling endpoint, or use WebFlux/SSE to stream the result.
    private final WorkflowClient workflowClient;
    private final TemporalProperties temporalProperties;
    private final RateLimitService rateLimitService;

    public CheckoutController(WorkflowClient workflowClient,
                              TemporalProperties temporalProperties,
                              RateLimitService rateLimitService) {
        this.workflowClient = workflowClient;
        this.temporalProperties = temporalProperties;
        this.rateLimitService = rateLimitService;
    }

    @PostMapping
    public ResponseEntity<CheckoutResponse> checkout(
            @RequestBody @Valid CheckoutRequest request,
            @AuthenticationPrincipal String principal) {
        UUID userId = principal != null ? UUID.fromString(principal) : request.userId();
        rateLimitService.checkCheckout(userId);
        CheckoutRequest resolved = request.userId() != null && request.userId().equals(userId)
            ? request
            : new CheckoutRequest(
                userId,
                request.storeId(),
                request.items(),
                request.subtotalCents(),
                request.discountCents(),
                request.totalCents(),
                request.currency(),
                request.couponCode(),
                request.idempotencyKey(),
                request.deliveryAddress());
        String workflowId = "checkout-" + resolved.idempotencyKey();
        WorkflowOptions options = WorkflowOptions.newBuilder()
            .setTaskQueue(temporalProperties.getTaskQueue())
            .setWorkflowId(workflowId)
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setWorkflowExecutionTimeout(Duration.ofMinutes(5))
            .build();
        CheckoutWorkflow workflow = workflowClient.newWorkflowStub(CheckoutWorkflow.class, options);
        try {
            CheckoutResult result = workflow.execute(resolved);
            if (result.isSuccess()) {
                return ResponseEntity.ok(new CheckoutResponse(result.getOrderId(), workflowId));
            }
            return ResponseEntity.unprocessableEntity()
                .body(new CheckoutResponse(null, null, result.getErrorMessage()));
        } catch (WorkflowExecutionAlreadyStarted ex) {
            throw new DuplicateCheckoutException(resolved.idempotencyKey());
        }
    }
}
