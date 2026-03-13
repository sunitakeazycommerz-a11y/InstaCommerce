package com.instacommerce.order.controller;

import com.instacommerce.order.config.OrderProperties;
import com.instacommerce.order.config.TemporalProperties;
import com.instacommerce.order.dto.request.CheckoutRequest;
import com.instacommerce.order.dto.response.CheckoutResponse;
import com.instacommerce.order.exception.ApiException;
import com.instacommerce.order.exception.DuplicateCheckoutException;
import com.instacommerce.order.service.RateLimitService;
import com.instacommerce.order.workflow.CheckoutWorkflow;
import com.instacommerce.order.workflow.model.CheckoutResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @deprecated Checkout authority moved to checkout-orchestrator-service (ADR-001).
 * This controller remains only for rollback safety behind the
 * {@code order.checkout.direct-saga-enabled} feature flag.
 */
@Deprecated(since = "wave-22", forRemoval = true)
@RestController
@RequestMapping("/checkout")
public class CheckoutController {
    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    // TODO: Convert to async (non-blocking) checkout. Currently blocks the HTTP thread
    // for the entire Temporal workflow execution. Future: return 202 Accepted with a
    // polling endpoint, or use WebFlux/SSE to stream the result.
    private final ObjectProvider<io.temporal.client.WorkflowClient> workflowClientProvider;
    private final TemporalProperties temporalProperties;
    private final OrderProperties orderProperties;
    private final RateLimitService rateLimitService;
    private final Counter legacyCheckoutCounter;

    public CheckoutController(ObjectProvider<io.temporal.client.WorkflowClient> workflowClientProvider,
                              TemporalProperties temporalProperties,
                              OrderProperties orderProperties,
                              RateLimitService rateLimitService,
                              MeterRegistry meterRegistry) {
        this.workflowClientProvider = workflowClientProvider;
        this.temporalProperties = temporalProperties;
        this.orderProperties = orderProperties;
        this.rateLimitService = rateLimitService;
        this.legacyCheckoutCounter = Counter.builder("checkout.legacy_path.invocations")
            .description("Invocations of the deprecated order-service checkout path")
            .register(meterRegistry);
    }

    @PostMapping
    public ResponseEntity<CheckoutResponse> checkout(
            @RequestBody @Valid CheckoutRequest request,
            @AuthenticationPrincipal String principal) {
        if (!orderProperties.getCheckout().isDirectSagaEnabled()) {
            throw new ApiException(
                HttpStatus.GONE,
                "CHECKOUT_MOVED",
                "Checkout is handled by checkout-orchestrator-service");
        }
        log.warn("Legacy checkout path invoked for user {}. This path is deprecated (ADR-001). "
                + "Migrate to checkout-orchestrator-service.", request.userId());
        legacyCheckoutCounter.increment();
        UUID userId = principal != null ? UUID.fromString(principal) : request.userId();
        rateLimitService.checkCheckout(userId);
        io.temporal.client.WorkflowClient workflowClient = workflowClientProvider.getIfAvailable();
        if (workflowClient == null) {
            throw new IllegalStateException("WorkflowClient is not configured while direct checkout saga is enabled");
        }
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
                return ResponseEntity.ok(new CheckoutResponse(result.orderId(), workflowId));
            }
            return ResponseEntity.unprocessableEntity()
                .body(new CheckoutResponse(null, null, result.errorMessage()));
        } catch (WorkflowExecutionAlreadyStarted ex) {
            throw new DuplicateCheckoutException(resolved.idempotencyKey());
        }
    }
}
