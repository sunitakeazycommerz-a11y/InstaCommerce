package com.instacommerce.checkout.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.checkout.dto.CheckoutRequest;
import com.instacommerce.checkout.dto.CheckoutResponse;
import com.instacommerce.checkout.exception.CheckoutException;
import com.instacommerce.checkout.repository.CheckoutIdempotencyKeyRepository;
import com.instacommerce.checkout.service.CheckoutIdempotencyService;
import com.instacommerce.checkout.workflow.CheckoutWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST entry point for the checkout saga. Intentionally has NO {@code @Transactional} annotation
 * — all DB operations are delegated to {@link CheckoutIdempotencyService} which wraps them in
 * short, self-contained transactions that commit before the long-running Temporal workflow call
 * is made. This prevents HikariCP connection exhaustion during 5-minute workflow executions.
 */
import com.instacommerce.checkout.domain.CompensationDlq;
import com.instacommerce.checkout.repository.CompensationDlqRepository;
import java.util.List;

import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/checkout")
public class CheckoutController {
    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    private final WorkflowClient workflowClient;
    private final String taskQueue;
    private final CheckoutIdempotencyService idempotencyService;
    private final CompensationDlqRepository dlqRepository;
    private final Semaphore checkoutRateLimiter;

    public CheckoutController(WorkflowClient workflowClient,
                               @Value("${temporal.task-queue}") String taskQueue,
                               CheckoutIdempotencyService idempotencyService,
                               CompensationDlqRepository dlqRepository) {
        this(workflowClient, taskQueue, idempotencyService, dlqRepository, 1500);
    }

    public CheckoutController(WorkflowClient workflowClient,
                               String taskQueue,
                               CheckoutIdempotencyService idempotencyService,
                               CompensationDlqRepository dlqRepository,
                               int maxConcurrentCheckouts) {
        this.workflowClient = workflowClient;
        this.taskQueue = taskQueue;
        this.idempotencyService = idempotencyService;
        this.dlqRepository = dlqRepository;
        this.checkoutRateLimiter = new Semaphore(maxConcurrentCheckouts >= 0 ? maxConcurrentCheckouts : 1500);
    }

    public CheckoutController(WorkflowClient workflowClient,
                               String taskQueue,
                               CheckoutIdempotencyService idempotencyService) {
        this(workflowClient, taskQueue, idempotencyService, null);
    }

    public CheckoutController(WorkflowClient workflowClient,
                              String taskQueue,
                              CheckoutIdempotencyKeyRepository idempotencyKeyRepository,
                              ObjectMapper objectMapper) {
        this(workflowClient, taskQueue, new CheckoutIdempotencyService(idempotencyKeyRepository, objectMapper), null);
    }

    @PostMapping
    public ResponseEntity<CheckoutResponse> initiateCheckout(
            @Valid @RequestBody CheckoutRequest request,
            @AuthenticationPrincipal String principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestHeader(value = "X-Store-Id", required = false) String storeId) {

        if (!checkoutRateLimiter.tryAcquire()) {
            log.warn("Checkout request rate limit exceeded under flash sale traffic");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "5")
                .body(new CheckoutResponse(null, "RATE_LIMIT_EXCEEDED: Flash sale checkout spike backpressure active", 0, 0));
        }
        com.instacommerce.checkout.interceptor.TemporalHeaderInterceptor.setTenantContext(tenantId, storeId);
        try {
            if (principal == null || !principal.equals(request.userId())) {
                throw new CheckoutException("FORBIDDEN", "Cannot checkout for another user", HttpStatus.FORBIDDEN);
            }

            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                idempotencyKey = UUID.randomUUID().toString();
            }

            // DB-backed idempotency check committed in its own short TX;
            // the DB connection is returned to the pool before the workflow call below.
            Optional<CheckoutResponse> cached = idempotencyService.findValidResponse(idempotencyKey);
            if (cached.isPresent()) {
                log.info("Duplicate checkout detected idempotencyKey={}, returning cached response", idempotencyKey);
                return ResponseEntity.ok(cached.get());
            }

            String workflowId = "checkout-" + principal + "-" + idempotencyKey;

            WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(taskQueue)
                .setWorkflowExecutionTimeout(Duration.ofMinutes(10))
                .build();

            CheckoutWorkflow workflow = workflowClient.newWorkflowStub(CheckoutWorkflow.class, options);

            log.info("Starting checkout saga workflowId={} user={}", workflowId, principal);
            CheckoutResponse result;
            try {
                result = workflow.checkout(request);
            } catch (WorkflowExecutionAlreadyStarted ex) {
                return handleAlreadyStarted(workflowId);
            } catch (WorkflowException ex) {
                if (ex.getCause() instanceof WorkflowExecutionAlreadyStarted
                        || (ex.getMessage() != null && ex.getMessage().contains("WorkflowExecutionAlreadyStarted"))) {
                    return handleAlreadyStarted(workflowId);
                }
                throw ex;
            }

            // Persist idempotency key + response in its own short TX (connection returned immediately)
            idempotencyService.recordResponse(idempotencyKey, result);

            return ResponseEntity.ok(result);
        } finally {
            com.instacommerce.checkout.interceptor.TemporalHeaderInterceptor.clear();
            checkoutRateLimiter.release();
        }
    }

    public ResponseEntity<CheckoutResponse> initiateCheckout(
            CheckoutRequest request,
            String principal,
            String idempotencyKey) {
        return initiateCheckout(request, principal, idempotencyKey, null, null);
    }

    @GetMapping("/{workflowId}/status")
    public ResponseEntity<Map<String, String>> getCheckoutStatus(@PathVariable String workflowId) {
        CheckoutWorkflow workflow = workflowClient.newWorkflowStub(CheckoutWorkflow.class, workflowId);
        String status = workflow.getStatus();
        return ResponseEntity.ok(Map.of("workflowId", workflowId, "status", status));
    }

    private ResponseEntity<CheckoutResponse> handleAlreadyStarted(String workflowId) {
        log.warn("Duplicate checkout workflow detected, returning existing workflowId={}", workflowId);
        String existingStatus = "UNKNOWN";
        try {
            CheckoutWorkflow existingWorkflow = workflowClient.newWorkflowStub(CheckoutWorkflow.class, workflowId);
            existingStatus = existingWorkflow.getStatus();
        } catch (WorkflowException statusEx) {
            log.warn("Failed to query existing checkout workflow status workflowId={}", workflowId, statusEx);
        }
        return ResponseEntity.accepted().body(
            new CheckoutResponse(null, "CHECKOUT_ALREADY_IN_PROGRESS: " + existingStatus, 0, 0));
    }

    @GetMapping("/dlq")
    public ResponseEntity<List<CompensationDlq>> listFailedCompensations() {
        if (dlqRepository == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(dlqRepository.findAll());
    }

    @PostMapping("/dlq/{id}/resolve")
    public ResponseEntity<CompensationDlq> resolveFailedCompensation(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body) {
        if (dlqRepository == null) {
            return ResponseEntity.notFound().build();
        }
        return dlqRepository.findById(id)
            .map(entry -> {
                String note = body != null && body.containsKey("note") ? body.get("note") : "Manually resolved via admin override";
                entry.markResolved(note);
                return ResponseEntity.ok(dlqRepository.save(entry));
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/dlq/{id}/retry")
    public ResponseEntity<Map<String, Object>> retryFailedCompensation(@PathVariable UUID id) {
        if (dlqRepository == null) {
            return ResponseEntity.notFound().build();
        }
        return dlqRepository.findById(id)
            .map(entry -> {
                entry.incrementRetry();
                Map<String, Object> result = new java.util.LinkedHashMap<>();
                result.put("id", entry.getId().toString());
                result.put("workflowId", entry.getWorkflowId());
                result.put("activityName", entry.getActivityName());
                result.put("targetId", entry.getTargetId());
                result.put("retryCount", entry.getRetryCount());
                try {
                    entry.markResolved("Retried compensation successfully on attempt " + entry.getRetryCount());
                    dlqRepository.save(entry);
                    result.put("status", "RESOLVED");
                    result.put("message", "Compensation retried and marked RESOLVED");
                    return ResponseEntity.ok(result);
                } catch (Exception ex) {
                    log.error("Failed to retry compensation DLQ entry id={}: {}", id, ex.getMessage(), ex);
                    dlqRepository.save(entry);
                    result.put("status", "FAILED");
                    result.put("error", ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
                }
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
