package com.instacommerce.checkout.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.checkout.domain.CheckoutIdempotencyKey;
import com.instacommerce.checkout.dto.CheckoutRequest;
import com.instacommerce.checkout.dto.CheckoutResponse;
import com.instacommerce.checkout.exception.CheckoutException;
import com.instacommerce.checkout.repository.CheckoutIdempotencyKeyRepository;
import com.instacommerce.checkout.workflow.CheckoutWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/checkout")
public class CheckoutController {
    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);
    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(30);

    private final WorkflowClient workflowClient;
    private final String taskQueue;
    private final CheckoutIdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

    public CheckoutController(WorkflowClient workflowClient,
                               @Value("${temporal.task-queue}") String taskQueue,
                               CheckoutIdempotencyKeyRepository idempotencyKeyRepository,
                               ObjectMapper objectMapper) {
        this.workflowClient = workflowClient;
        this.taskQueue = taskQueue;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<CheckoutResponse> initiateCheckout(
            @Valid @RequestBody CheckoutRequest request,
            @AuthenticationPrincipal String principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        if (principal == null || !principal.equals(request.userId())) {
            throw new CheckoutException("FORBIDDEN", "Cannot checkout for another user", HttpStatus.FORBIDDEN);
        }

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = UUID.randomUUID().toString();
        }

        // DB-backed idempotency check: return cached response if key exists and not expired
        Optional<CheckoutIdempotencyKey> existing = idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            CheckoutIdempotencyKey cached = existing.get();
            if (cached.getExpiresAt().isAfter(Instant.now())) {
                log.info("Duplicate checkout detected idempotencyKey={}, returning cached response", idempotencyKey);
                return ResponseEntity.ok(deserializeResponse(cached.getCheckoutResponse()));
            }
            log.info("Expired idempotency key found idempotencyKey={}, proceeding with new checkout", idempotencyKey);
        }

        String workflowId = "checkout-" + principal + "-" + idempotencyKey;

        WorkflowOptions options = WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(taskQueue)
            .setWorkflowExecutionTimeout(Duration.ofMinutes(5))
            .build();

        CheckoutWorkflow workflow = workflowClient.newWorkflowStub(CheckoutWorkflow.class, options);

        log.info("Starting checkout saga workflowId={} user={}", workflowId, principal);
        CheckoutResponse result = workflow.checkout(request);

        // Persist idempotency key with response for durable duplicate detection
        Instant expiresAt = Instant.now().plus(IDEMPOTENCY_TTL);
        CheckoutIdempotencyKey key = new CheckoutIdempotencyKey(
            idempotencyKey, serializeResponse(result), expiresAt);
        idempotencyKeyRepository.save(key);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/{workflowId}/status")
    public ResponseEntity<Map<String, String>> getCheckoutStatus(@PathVariable String workflowId) {
        CheckoutWorkflow workflow = workflowClient.newWorkflowStub(CheckoutWorkflow.class, workflowId);
        String status = workflow.getStatus();
        return ResponseEntity.ok(Map.of("workflowId", workflowId, "status", status));
    }

    private String serializeResponse(CheckoutResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize checkout response", e);
        }
    }

    private CheckoutResponse deserializeResponse(String json) {
        try {
            return objectMapper.readValue(json, CheckoutResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize cached checkout response", e);
        }
    }
}
