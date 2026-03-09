package com.instacommerce.checkout.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.checkout.domain.CheckoutIdempotencyKey;
import com.instacommerce.checkout.dto.CheckoutRequest;
import com.instacommerce.checkout.dto.CheckoutResponse;
import com.instacommerce.checkout.dto.ErrorDetail;
import com.instacommerce.checkout.exception.CheckoutException;
import com.instacommerce.checkout.repository.CheckoutIdempotencyKeyRepository;
import com.instacommerce.checkout.workflow.CheckoutWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class CheckoutControllerTest {

    private static final String TASK_QUEUE = "CHECKOUT_ORCHESTRATOR_TASK_QUEUE";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private WorkflowClient workflowClient;

    @Mock
    private CheckoutIdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private CheckoutWorkflow workflow;

    @Mock
    private CheckoutWorkflow existingWorkflow;

    private CheckoutController controller;

    @BeforeEach
    void setUp() {
        controller = new CheckoutController(workflowClient, TASK_QUEUE, idempotencyKeyRepository, objectMapper);
    }

    @Test
    void returnsCachedResponseWhenIdempotencyKeyIsStillLive() throws Exception {
        CheckoutRequest request = request();
        CheckoutResponse cachedResponse = CheckoutResponse.success("order-123", 2599, 18);
        CheckoutIdempotencyKey cachedKey = new CheckoutIdempotencyKey(
            "idem-123",
            objectMapper.writeValueAsString(cachedResponse),
            Instant.now().plus(Duration.ofMinutes(5))
        );
        when(idempotencyKeyRepository.findByIdempotencyKey("idem-123")).thenReturn(Optional.of(cachedKey));

        ResponseEntity<CheckoutResponse> response = controller.initiateCheckout(request, "user-123", "idem-123");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(cachedResponse);
        verifyNoInteractions(workflowClient);
    }

    @Test
    void surfacesConflictWithLiveWorkflowStatusWhenWorkflowAlreadyExists() {
        CheckoutRequest request = request();
        String workflowId = "checkout-user-123-idem-123";
        when(idempotencyKeyRepository.findByIdempotencyKey("idem-123")).thenReturn(Optional.empty());
        when(workflowClient.newWorkflowStub(eq(CheckoutWorkflow.class), any(WorkflowOptions.class))).thenReturn(workflow);
        when(workflow.checkout(any(CheckoutRequest.class))).thenThrow(new WorkflowExecutionAlreadyStarted(
            WorkflowExecution.newBuilder().setWorkflowId(workflowId).setRunId("run-123").build(),
            CheckoutWorkflow.class.getSimpleName(),
            null
        ));
        when(workflowClient.newWorkflowStub(CheckoutWorkflow.class, workflowId)).thenReturn(existingWorkflow);
        when(existingWorkflow.getStatus()).thenReturn("AUTHORIZING_PAYMENT");

        CheckoutException ex = assertThrows(
            CheckoutException.class,
            () -> controller.initiateCheckout(request, "user-123", "idem-123")
        );

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getCode()).isEqualTo("CHECKOUT_ALREADY_IN_PROGRESS");
        assertThat(ex.getDetails()).containsExactly(
            new ErrorDetail("workflowId", workflowId),
            new ErrorDetail("status", "AUTHORIZING_PAYMENT"),
            new ErrorDetail("statusPath", "/checkout/" + workflowId + "/status")
        );
    }

    @Test
    void replacesExpiredIdempotencyKeyBeforeSavingFreshResult() throws Exception {
        CheckoutRequest request = request();
        CheckoutResponse freshResponse = CheckoutResponse.success("order-456", 3199, 14);
        CheckoutIdempotencyKey expiredKey = new CheckoutIdempotencyKey(
            "idem-123",
            objectMapper.writeValueAsString(CheckoutResponse.success("old-order", 2599, 18)),
            Instant.now().minus(Duration.ofMinutes(1))
        );
        when(idempotencyKeyRepository.findByIdempotencyKey("idem-123")).thenReturn(Optional.of(expiredKey));
        when(workflowClient.newWorkflowStub(eq(CheckoutWorkflow.class), any(WorkflowOptions.class))).thenReturn(workflow);
        when(workflow.checkout(request)).thenReturn(freshResponse);

        ResponseEntity<CheckoutResponse> response = controller.initiateCheckout(request, "user-123", "idem-123");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(freshResponse);
        verify(idempotencyKeyRepository).delete(expiredKey);
        verify(idempotencyKeyRepository).flush();
        ArgumentCaptor<CheckoutIdempotencyKey> savedCaptor = ArgumentCaptor.forClass(CheckoutIdempotencyKey.class);
        verify(idempotencyKeyRepository).save(savedCaptor.capture());
        CheckoutIdempotencyKey savedKey = savedCaptor.getValue();
        assertThat(savedKey.getIdempotencyKey()).isEqualTo("idem-123");
        assertThat(savedKey.getCheckoutResponse()).isEqualTo(objectMapper.writeValueAsString(freshResponse));
        assertThat(savedKey.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(29)));
    }

    private CheckoutRequest request() {
        return new CheckoutRequest("user-123", "pm-123", "SAVE20", "addr-123");
    }
}
