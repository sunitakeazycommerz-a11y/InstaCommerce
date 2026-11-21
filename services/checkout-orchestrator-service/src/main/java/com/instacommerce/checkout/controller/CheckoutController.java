package com.instacommerce.checkout.controller;

import com.instacommerce.checkout.domain.CompensationDlq;
import com.instacommerce.checkout.dto.CheckoutRequest;
import com.instacommerce.checkout.dto.CheckoutResponse;
import com.instacommerce.checkout.exception.CheckoutException;
import com.instacommerce.checkout.interceptor.TemporalHeaderInterceptor;
import com.instacommerce.checkout.repository.CompensationDlqRepository;
import com.instacommerce.checkout.service.CheckoutIdempotencyService;
import com.instacommerce.checkout.service.CompensationReplayService;
import com.instacommerce.checkout.workflow.CheckoutWorkflow;
import com.instacommerce.platform.security.SecurityRoleUtils;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST entry point for the checkout saga. DB idempotency work is delegated to
 * {@link CheckoutIdempotencyService} so connections return to the pool before
 * invoking Temporal.
 */
@RestController
@RequestMapping({"/checkout", "/api/v1/checkout"})
public class CheckoutController {
    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    private final WorkflowClient workflowClient;
    private final String taskQueue;
    private final CheckoutIdempotencyService idempotencyService;
    private final CompensationDlqRepository dlqRepository;
    private final CompensationReplayService compensationReplayService;
    private final Semaphore checkoutRateLimiter;

    public CheckoutController(WorkflowClient workflowClient,
                              @Value("${temporal.task-queue}") String taskQueue,
                              CheckoutIdempotencyService idempotencyService,
                              @Nullable CompensationDlqRepository dlqRepository,
                              @Nullable CompensationReplayService compensationReplayService,
                              @Value("${checkout.concurrency.max-in-flight:1500}") int maxConcurrentCheckouts) {
        this.workflowClient = workflowClient;
        this.taskQueue = taskQueue;
        this.idempotencyService = idempotencyService;
        this.dlqRepository = dlqRepository;
        this.compensationReplayService = compensationReplayService;
        this.checkoutRateLimiter = new Semaphore(maxConcurrentCheckouts >= 0 ? maxConcurrentCheckouts : 1500);
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
                .body(CheckoutResponse.failed("RATE_LIMIT_EXCEEDED: Flash sale checkout spike backpressure active"));
        }
        TemporalHeaderInterceptor.setTenantContext(tenantId, storeId);
        try {
            if (principal == null || !principal.equals(request.userId())) {
                throw new CheckoutException("FORBIDDEN", "Cannot checkout for another user", HttpStatus.FORBIDDEN);
            }

            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                idempotencyKey = UUID.randomUUID().toString();
            }
            String scopedIdempotencyKey = principal + ":" + idempotencyKey;

            Optional<CheckoutResponse> cached = idempotencyService.findValidResponse(scopedIdempotencyKey);
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
                return handleAlreadyStarted(workflowId, scopedIdempotencyKey);
            } catch (WorkflowException ex) {
                if (ex.getCause() instanceof WorkflowExecutionAlreadyStarted
                    || (ex.getMessage() != null && ex.getMessage().contains("WorkflowExecutionAlreadyStarted"))) {
                    return handleAlreadyStarted(workflowId, scopedIdempotencyKey);
                }
                throw ex;
            }

            try {
                idempotencyService.recordResponse(scopedIdempotencyKey, result);
            } catch (DataIntegrityViolationException dupEx) {
                log.warn("Duplicate idempotency response save ignored workflowId={}", workflowId, dupEx);
            }

            return ResponseEntity.ok(result);
        } finally {
            TemporalHeaderInterceptor.clear();
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
    public ResponseEntity<Map<String, String>> getCheckoutStatus(
            @PathVariable String workflowId,
            Authentication authentication) {
        if (!isAuthorizedForWorkflow(authentication, workflowId)) {
            throw new CheckoutException("FORBIDDEN", "Cannot access another user's checkout status", HttpStatus.FORBIDDEN);
        }
        CheckoutWorkflow workflow = workflowClient.newWorkflowStub(CheckoutWorkflow.class, workflowId);
        String status = workflow.getStatus();
        return ResponseEntity.ok(Map.of("workflowId", workflowId, "status", status));
    }

    private boolean isAuthorizedForWorkflow(Authentication authentication, String workflowId) {
        if (authentication == null) {
            return false;
        }
        if (SecurityRoleUtils.isPrivileged(authentication)) {
            return true;
        }
        String principal = authentication.getName();
        return principal != null && !principal.isBlank()
            && workflowId != null && workflowId.startsWith("checkout-" + principal + "-");
    }

    private ResponseEntity<CheckoutResponse> handleAlreadyStarted(String workflowId, String scopedIdempotencyKey) {
        Optional<CheckoutResponse> recorded = idempotencyService.findValidResponse(scopedIdempotencyKey);
        if (recorded.isPresent()) {
            log.info("Returning recorded idempotent response after concurrent checkout start workflowId={}", workflowId);
            return ResponseEntity.ok(recorded.get());
        }
        log.warn("Duplicate checkout workflow detected, returning existing workflowId={}", workflowId);
        String existingStatus = "UNKNOWN";
        CheckoutResponse snapshot = null;
        try {
            CheckoutWorkflow existingWorkflow = workflowClient.newWorkflowStub(CheckoutWorkflow.class, workflowId);
            existingStatus = existingWorkflow.getStatus();
            snapshot = existingWorkflow.getResultSnapshot();
        } catch (WorkflowException statusEx) {
            log.warn("Failed to query existing checkout workflow status workflowId={}", workflowId, statusEx);
        }
        if (snapshot != null) {
            if ("COMPLETED".equalsIgnoreCase(existingStatus)) {
                return ResponseEntity.ok(snapshot);
            }
            return ResponseEntity.accepted().body(snapshot);
        }
        if ("COMPLETED".equalsIgnoreCase(existingStatus)) {
            return ResponseEntity.accepted().body(
                new CheckoutResponse(null, "CHECKOUT_ALREADY_COMPLETED", 0, 0));
        }
        if ("FAILED".equalsIgnoreCase(existingStatus)) {
            return ResponseEntity.accepted().body(
                new CheckoutResponse(null, "CHECKOUT_ALREADY_FAILED", 0, 0));
        }
        return ResponseEntity.accepted().body(
            new CheckoutResponse(null, "CHECKOUT_ALREADY_IN_PROGRESS: " + existingStatus, 0, 0));
    }

    @GetMapping("/dlq")
    @PreAuthorize("hasAnyRole('ADMIN', 'INTERNAL_SERVICE')")
    public ResponseEntity<List<CompensationDlq>> listFailedCompensations() {
        if (dlqRepository == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(dlqRepository.findAll());
    }

    @PostMapping("/dlq/{id}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN', 'INTERNAL_SERVICE')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'INTERNAL_SERVICE')")
    public ResponseEntity<Map<String, Object>> retryFailedCompensation(@PathVariable UUID id) {
        if (dlqRepository == null) {
            return ResponseEntity.notFound().build();
        }
        return dlqRepository.findById(id)
            .map(entry -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", entry.getId() != null ? entry.getId().toString() : id.toString());
                result.put("workflowId", entry.getWorkflowId());
                result.put("activityName", entry.getActivityName());
                result.put("targetId", entry.getTargetId());
                if ("RESOLVED".equalsIgnoreCase(entry.getStatus())) {
                    result.put("retryCount", entry.getRetryCount());
                    result.put("status", "RESOLVED");
                    result.put("message", "Compensation already resolved");
                    return ResponseEntity.ok(result);
                }
                entry.incrementRetry();
                result.put("retryCount", entry.getRetryCount());
                if (compensationReplayService == null) {
                    String unavailableMessage = "CompensationReplayService unavailable for replay";
                    entry.recordRetryFailure(unavailableMessage);
                    dlqRepository.save(entry);
                    result.put("status", entry.getStatus());
                    result.put("error", unavailableMessage);
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
                }
                try {
                    compensationReplayService.replay(entry);
                    entry.markResolved("Retried compensation successfully on attempt " + entry.getRetryCount());
                    dlqRepository.save(entry);
                    result.put("status", entry.getStatus());
                    result.put("message", "Compensation retried and marked RESOLVED");
                    return ResponseEntity.ok(result);
                } catch (Exception ex) {
                    log.error("Failed to retry compensation DLQ entry id={}: {}", id, ex.getMessage(), ex);
                    entry.recordRetryFailure(ex.getMessage());
                    dlqRepository.save(entry);
                    result.put("status", entry.getStatus());
                    result.put("error", ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
                }
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
