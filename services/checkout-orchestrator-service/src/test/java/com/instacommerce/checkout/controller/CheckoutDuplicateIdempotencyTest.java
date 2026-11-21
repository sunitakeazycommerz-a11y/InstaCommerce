package com.instacommerce.checkout.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.checkout.domain.CheckoutIdempotencyKey;
import com.instacommerce.checkout.domain.CompensationDlq;
import com.instacommerce.checkout.dto.CheckoutRequest;
import com.instacommerce.checkout.dto.CheckoutResponse;
import com.instacommerce.checkout.repository.CheckoutIdempotencyKeyRepository;
import com.instacommerce.checkout.repository.CompensationDlqRepository;
import com.instacommerce.checkout.service.CheckoutIdempotencyService;
import com.instacommerce.checkout.service.CompensationReplayService;
import com.instacommerce.checkout.workflow.CheckoutWorkflow;
import com.instacommerce.checkout.workflow.activity.CouponActivity;
import com.instacommerce.checkout.workflow.activity.InventoryActivity;
import com.instacommerce.checkout.workflow.activity.OrderActivity;
import com.instacommerce.checkout.workflow.activity.PaymentActivity;
import com.instacommerce.checkout.workflow.activity.WalletActivity;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class CheckoutDuplicateIdempotencyTest {

    @Mock
    private WorkflowClient workflowClient;
    @Mock
    private CheckoutIdempotencyKeyRepository idempotencyKeyRepository;
    @Mock
    private CompensationDlqRepository dlqRepository;
    @Mock
    private CheckoutWorkflow workflow;

    private ObjectMapper objectMapper;
    private CheckoutController controller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        CheckoutIdempotencyService idempotencyService =
            new CheckoutIdempotencyService(idempotencyKeyRepository, objectMapper);
        CompensationReplayService replayService = new CompensationReplayService(
            mock(InventoryActivity.class),
            mock(PaymentActivity.class),
            mock(OrderActivity.class),
            mock(CouponActivity.class),
            mock(WalletActivity.class)
        );
        controller = new CheckoutController(
            workflowClient,
            "CHECKOUT_TASK_QUEUE",
            idempotencyService,
            dlqRepository,
            replayService,
            1500
        );
    }

    @Test
    void duplicateWorkflowStartReturnsCompletedSnapshotWhenAlreadyFinished() {
        CheckoutRequest request = new CheckoutRequest("user-1", "pm-1", null, "addr-1");
        String workflowId = "checkout-user-1-ik-done";
        CheckoutResponse completed = CheckoutResponse.success("ord-99", 4500L, 12);

        when(idempotencyKeyRepository.findByIdempotencyKey("user-1:ik-done")).thenReturn(Optional.empty());
        when(workflowClient.newWorkflowStub(eq(CheckoutWorkflow.class), any(WorkflowOptions.class)))
            .thenReturn(workflow);
        when(workflow.checkout(request)).thenThrow(new WorkflowExecutionAlreadyStarted(
            WorkflowExecution.getDefaultInstance(), "CheckoutWorkflow", null));

        CheckoutWorkflow existingStub = mock(CheckoutWorkflow.class);
        when(workflowClient.newWorkflowStub(CheckoutWorkflow.class, workflowId)).thenReturn(existingStub);
        when(existingStub.getStatus()).thenReturn("COMPLETED");
        when(existingStub.getResultSnapshot()).thenReturn(completed);

        ResponseEntity<CheckoutResponse> response = controller.initiateCheckout(request, "user-1", "ik-done");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(completed);
    }

    @Test
    void duplicateWorkflowStartReturnsRecordedResponseIfPersistedBeforeStatusQuery() throws Exception {
        CheckoutRequest request = new CheckoutRequest("user-1", "pm-1", null, "addr-1");
        CheckoutResponse completed = CheckoutResponse.success("ord-88", 3200L, 10);
        CheckoutIdempotencyKey recorded = new CheckoutIdempotencyKey(
            "user-1:ik-race",
            objectMapper.writeValueAsString(completed),
            Instant.now().plus(Duration.ofHours(1))
        );

        when(idempotencyKeyRepository.findByIdempotencyKey("user-1:ik-race"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(recorded));
        when(workflowClient.newWorkflowStub(eq(CheckoutWorkflow.class), any(WorkflowOptions.class)))
            .thenReturn(workflow);
        when(workflow.checkout(request)).thenThrow(new WorkflowExecutionAlreadyStarted(
            WorkflowExecution.getDefaultInstance(), "CheckoutWorkflow", null));

        ResponseEntity<CheckoutResponse> response = controller.initiateCheckout(request, "user-1", "ik-race");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(completed);
    }

    @Test
    void duplicateWorkflowStartReportsCompletedTerminalStatusEvenWithoutSnapshot() {
        CheckoutRequest request = new CheckoutRequest("user-1", "pm-1", null, "addr-1");
        String workflowId = "checkout-user-1-ik-nosnap";

        when(idempotencyKeyRepository.findByIdempotencyKey("user-1:ik-nosnap")).thenReturn(Optional.empty());
        when(workflowClient.newWorkflowStub(eq(CheckoutWorkflow.class), any(WorkflowOptions.class)))
            .thenReturn(workflow);
        when(workflow.checkout(request)).thenThrow(new WorkflowExecutionAlreadyStarted(
            WorkflowExecution.getDefaultInstance(), "CheckoutWorkflow", null));

        CheckoutWorkflow existingStub = mock(CheckoutWorkflow.class);
        when(workflowClient.newWorkflowStub(CheckoutWorkflow.class, workflowId)).thenReturn(existingStub);
        when(existingStub.getStatus()).thenReturn("FAILED");
        when(existingStub.getResultSnapshot()).thenReturn(null);

        ResponseEntity<CheckoutResponse> response = controller.initiateCheckout(request, "user-1", "ik-nosnap");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().status()).isEqualTo("CHECKOUT_ALREADY_FAILED");
    }

    @Test
    void ignoresConcurrentIdempotencyInsertRaceAfterWorkflowCompletion() {
        CheckoutRequest request = new CheckoutRequest("user-1", "pm-1", null, "addr-1");
        CheckoutResponse completed = CheckoutResponse.success("ord-77", 1900L, 15);

        when(idempotencyKeyRepository.findByIdempotencyKey("user-1:ik-conc")).thenReturn(Optional.empty());
        when(workflowClient.newWorkflowStub(eq(CheckoutWorkflow.class), any(WorkflowOptions.class)))
            .thenReturn(workflow);
        when(workflow.checkout(request)).thenReturn(completed);
        when(idempotencyKeyRepository.save(any(CheckoutIdempotencyKey.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key"));

        ResponseEntity<CheckoutResponse> response = controller.initiateCheckout(request, "user-1", "ik-conc");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(completed);
    }

    @Test
    void replaysWalletRefundCompensationFromDlq() {
        WalletActivity walletActivity = mock(WalletActivity.class);
        CompensationReplayService replayService = new CompensationReplayService(
            mock(InventoryActivity.class),
            mock(PaymentActivity.class),
            mock(OrderActivity.class),
            mock(CouponActivity.class),
            walletActivity
        );
        CompensationDlq entry = new CompensationDlq("wf-wallet-1", "refundWallet", "user-42", "timeout", "1250");

        replayService.replay(entry);

        verify(walletActivity).refundWallet(
            eq("user-42"),
            eq(1250L),
            eq("wf-wallet-1:wallet:refund"),
            any()
        );
    }
}
