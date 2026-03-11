package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.payment.controller.WebhookController;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.dto.request.AuthorizeRequest;
import com.instacommerce.payment.dto.request.RefundRequest;
import com.instacommerce.payment.exception.PaymentGatewayException;
import com.instacommerce.payment.gateway.GatewayAuthResult;
import com.instacommerce.payment.gateway.GatewayCaptureResult;
import com.instacommerce.payment.gateway.GatewayRefundResult;
import com.instacommerce.payment.gateway.GatewayVoidResult;
import com.instacommerce.payment.gateway.PaymentGateway;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.RefundRepository;
import com.instacommerce.payment.webhook.WebhookEventHandler;
import com.instacommerce.payment.webhook.WebhookEventProcessor;
import com.instacommerce.payment.webhook.WebhookSignatureVerifier;
import com.instacommerce.payment.repository.LedgerEntryRepository;
import com.instacommerce.payment.repository.ProcessedWebhookEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class ApiObservabilityTest {

    // --- PaymentService dependencies ---
    @Mock PaymentRepository paymentRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock PaymentTransactionHelper txHelper;

    // --- RefundService dependencies ---
    @Mock RefundRepository refundRepository;
    @Mock RefundTransactionHelper refundTxHelper;

    // --- WebhookController dependencies ---
    @Mock WebhookSignatureVerifier signatureVerifier;
    @Mock WebhookEventHandler webhookEventHandler;

    // --- WebhookEventProcessor dependencies ---
    @Mock ProcessedWebhookEventRepository processedWebhookEventRepository;
    @Mock LedgerEntryRepository ledgerEntryRepository;
    @Mock LedgerService ledgerService;
    @Mock OutboxService outboxService;
    @Mock AuditLogService auditLogService;

    SimpleMeterRegistry meterRegistry;
    PaymentService paymentService;
    RefundService refundService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        paymentService = new PaymentService(paymentRepository, paymentGateway, txHelper, meterRegistry);
        refundService = new RefundService(refundRepository, paymentGateway, refundTxHelper, meterRegistry);
    }

    // --- Helpers ---

    private Payment payment(PaymentStatus status) {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setOrderId(UUID.randomUUID());
        p.setAmountCents(10000);
        p.setCapturedCents(0);
        p.setRefundedCents(0);
        p.setCurrency("INR");
        p.setStatus(status);
        p.setPspReference("psp_" + UUID.randomUUID());
        p.setIdempotencyKey("key-" + UUID.randomUUID());
        p.setCreatedAt(Instant.now().minusSeconds(60));
        p.setUpdatedAt(Instant.now().minusSeconds(60));
        return p;
    }

    private double counterValue(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private long timerCount(String name, String... tags) {
        Timer timer = meterRegistry.find(name).tags(tags).timer();
        return timer != null ? timer.count() : 0;
    }

    // =========================================================================
    // Part 1: PaymentService metrics
    // =========================================================================

    @Nested
    @DisplayName("Authorize payment metrics")
    class AuthorizeMetrics {

        @Test
        @DisplayName("authorize success records success metrics")
        void authorizePayment_recordsSuccessMetrics() {
            AuthorizeRequest request = new AuthorizeRequest(
                UUID.randomUUID(), 5000, "INR", "idem-key-1", "card");
            Payment pending = payment(PaymentStatus.AUTHORIZE_PENDING);
            Payment saved = payment(PaymentStatus.AUTHORIZED);

            when(txHelper.savePendingAuthorization(any(), anyString())).thenReturn(pending);
            when(paymentGateway.authorize(any())).thenReturn(GatewayAuthResult.success("psp_ref_1"));
            when(txHelper.completeAuthorization(any(), anyString())).thenReturn(saved);

            paymentService.authorize(request);

            assertThat(counterValue("payment.operation.total",
                "operation", "authorize", "result", "success")).isEqualTo(1.0);
            assertThat(timerCount("payment.operation.duration",
                "operation", "authorize", "result", "success")).isEqualTo(1);
        }

        @Test
        @DisplayName("authorize failure records failure metrics")
        void authorizePayment_recordsFailureMetrics() {
            AuthorizeRequest request = new AuthorizeRequest(
                UUID.randomUUID(), 5000, "INR", "idem-key-2", "card");
            Payment pending = payment(PaymentStatus.AUTHORIZE_PENDING);

            when(txHelper.savePendingAuthorization(any(), anyString())).thenReturn(pending);
            when(paymentGateway.authorize(any())).thenThrow(new RuntimeException("Network timeout"));

            assertThatThrownBy(() -> paymentService.authorize(request))
                .isInstanceOf(RuntimeException.class);

            assertThat(counterValue("payment.operation.total",
                "operation", "authorize", "result", "failure")).isEqualTo(1.0);
            assertThat(timerCount("payment.operation.duration",
                "operation", "authorize", "result", "failure")).isEqualTo(1);
        }

        @Test
        @DisplayName("authorize gateway error records gateway_error metrics")
        void authorizePayment_recordsGatewayErrorMetrics() {
            AuthorizeRequest request = new AuthorizeRequest(
                UUID.randomUUID(), 5000, "INR", "idem-key-3", "card");
            Payment pending = payment(PaymentStatus.AUTHORIZE_PENDING);

            when(txHelper.savePendingAuthorization(any(), anyString())).thenReturn(pending);
            when(paymentGateway.authorize(any())).thenThrow(new PaymentGatewayException("Gateway unavailable"));

            assertThatThrownBy(() -> paymentService.authorize(request))
                .isInstanceOf(PaymentGatewayException.class);

            assertThat(counterValue("payment.operation.total",
                "operation", "authorize", "result", "gateway_error")).isEqualTo(1.0);
            assertThat(timerCount("payment.operation.duration",
                "operation", "authorize", "result", "gateway_error")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Capture payment metrics")
    class CaptureMetrics {

        @Test
        @DisplayName("capture success records metrics")
        void capturePayment_recordsMetrics() {
            UUID paymentId = UUID.randomUUID();
            Payment authorized = payment(PaymentStatus.AUTHORIZED);
            authorized.setId(paymentId);
            Payment captured = payment(PaymentStatus.CAPTURED);
            captured.setId(paymentId);

            when(txHelper.saveCapturePending(paymentId)).thenReturn(authorized);
            when(paymentGateway.capture(anyString(), anyLong(), any()))
                .thenReturn(GatewayCaptureResult.ok());
            when(txHelper.completeCaptured(eq(paymentId), anyLong())).thenReturn(captured);

            paymentService.capture(paymentId);

            assertThat(counterValue("payment.operation.total",
                "operation", "capture", "result", "success")).isEqualTo(1.0);
            assertThat(timerCount("payment.operation.duration",
                "operation", "capture", "result", "success")).isEqualTo(1);
        }

        @Test
        @DisplayName("capture gateway failure records gateway_error metrics")
        void capturePayment_recordsGatewayErrorMetrics() {
            UUID paymentId = UUID.randomUUID();
            Payment authorized = payment(PaymentStatus.AUTHORIZED);
            authorized.setId(paymentId);

            when(txHelper.saveCapturePending(paymentId)).thenReturn(authorized);
            when(paymentGateway.capture(anyString(), anyLong(), any()))
                .thenReturn(GatewayCaptureResult.failure("Capture declined"));

            assertThatThrownBy(() -> paymentService.capture(paymentId))
                .isInstanceOf(PaymentGatewayException.class);

            assertThat(counterValue("payment.operation.total",
                "operation", "capture", "result", "gateway_error")).isEqualTo(1.0);
            assertThat(timerCount("payment.operation.duration",
                "operation", "capture", "result", "gateway_error")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Void payment metrics")
    class VoidMetrics {

        @Test
        @DisplayName("void success records metrics")
        void voidPayment_recordsMetrics() {
            UUID paymentId = UUID.randomUUID();
            Payment authorized = payment(PaymentStatus.AUTHORIZED);
            authorized.setId(paymentId);
            Payment voided = payment(PaymentStatus.VOIDED);
            voided.setId(paymentId);

            when(txHelper.saveVoidPending(paymentId)).thenReturn(authorized);
            when(paymentGateway.voidAuth(anyString(), any()))
                .thenReturn(GatewayVoidResult.ok());
            when(txHelper.completeVoided(paymentId)).thenReturn(voided);

            paymentService.voidAuth(paymentId);

            assertThat(counterValue("payment.operation.total",
                "operation", "void", "result", "success")).isEqualTo(1.0);
            assertThat(timerCount("payment.operation.duration",
                "operation", "void", "result", "success")).isEqualTo(1);
        }

        @Test
        @DisplayName("void gateway failure records gateway_error metrics")
        void voidPayment_recordsGatewayErrorMetrics() {
            UUID paymentId = UUID.randomUUID();
            Payment authorized = payment(PaymentStatus.AUTHORIZED);
            authorized.setId(paymentId);

            when(txHelper.saveVoidPending(paymentId)).thenReturn(authorized);
            when(paymentGateway.voidAuth(anyString(), any()))
                .thenReturn(GatewayVoidResult.failure("Void rejected"));

            assertThatThrownBy(() -> paymentService.voidAuth(paymentId))
                .isInstanceOf(PaymentGatewayException.class);

            assertThat(counterValue("payment.operation.total",
                "operation", "void", "result", "gateway_error")).isEqualTo(1.0);
            assertThat(timerCount("payment.operation.duration",
                "operation", "void", "result", "gateway_error")).isEqualTo(1);
        }
    }

    // =========================================================================
    // Part 1b: RefundService metrics
    // =========================================================================

    @Nested
    @DisplayName("Refund payment metrics")
    class RefundMetrics {

        @Test
        @DisplayName("refund success records metrics")
        void refundPayment_recordsMetrics() {
            UUID paymentId = UUID.randomUUID();
            UUID refundId = UUID.randomUUID();
            RefundRequest request = new RefundRequest(3000, "customer_request", "refund-idem-1");

            var pendingResult = new RefundTransactionHelper.RefundPendingResult(refundId, "psp_ref_1");
            when(refundTxHelper.savePendingRefund(eq(paymentId), eq(request), anyString()))
                .thenReturn(pendingResult);
            when(paymentGateway.refund(anyString(), anyLong(), anyString(), any()))
                .thenReturn(GatewayRefundResult.success("psp_refund_1"));

            com.instacommerce.payment.domain.model.Refund savedRefund =
                new com.instacommerce.payment.domain.model.Refund();
            savedRefund.setId(refundId);
            savedRefund.setAmountCents(3000);
            savedRefund.setStatus(com.instacommerce.payment.domain.model.RefundStatus.COMPLETED);
            when(refundTxHelper.completeRefund(eq(refundId), eq(paymentId), eq(request), anyString()))
                .thenReturn(savedRefund);

            refundService.refund(paymentId, request);

            assertThat(counterValue("payment.refund.total", "result", "success")).isEqualTo(1.0);
            assertThat(timerCount("payment.refund.duration", "result", "success")).isEqualTo(1);
        }

        @Test
        @DisplayName("refund gateway error records gateway_error metrics")
        void refundPayment_recordsGatewayErrorMetrics() {
            UUID paymentId = UUID.randomUUID();
            UUID refundId = UUID.randomUUID();
            RefundRequest request = new RefundRequest(3000, "customer_request", "refund-idem-2");

            var pendingResult = new RefundTransactionHelper.RefundPendingResult(refundId, "psp_ref_1");
            when(refundTxHelper.savePendingRefund(eq(paymentId), eq(request), anyString()))
                .thenReturn(pendingResult);
            when(paymentGateway.refund(anyString(), anyLong(), anyString(), any()))
                .thenReturn(GatewayRefundResult.failure("Refund rejected by PSP"));

            assertThatThrownBy(() -> refundService.refund(paymentId, request))
                .isInstanceOf(PaymentGatewayException.class);

            assertThat(counterValue("payment.refund.total", "result", "gateway_error")).isEqualTo(1.0);
            assertThat(timerCount("payment.refund.duration", "result", "gateway_error")).isEqualTo(1);
        }
    }

    // =========================================================================
    // Part 3: Dispute updated outbox event
    // =========================================================================

    @Nested
    @DisplayName("Dispute updated outbox event")
    class DisputeUpdatedOutbox {

        @Test
        @DisplayName("dispute updated publishes outbox event")
        void disputeUpdated_publishesOutboxEvent() {
            Payment disputed = payment(PaymentStatus.DISPUTED);

            WebhookEventProcessor processor = new WebhookEventProcessor(
                paymentRepository,
                processedWebhookEventRepository,
                refundRepository,
                ledgerEntryRepository,
                ledgerService,
                outboxService,
                auditLogService,
                meterRegistry,
                false,
                false
            );

            String eventId = "evt_" + UUID.randomUUID();
            String pspRef = disputed.getPspReference();

            when(paymentRepository.findByPspReferenceForUpdate(pspRef))
                .thenReturn(Optional.of(disputed));

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode rootNode = mapper.createObjectNode();
            rootNode.put("id", eventId);
            rootNode.put("type", "charge.dispute.updated");
            com.fasterxml.jackson.databind.node.ObjectNode dataNode = rootNode.putObject("data");
            com.fasterxml.jackson.databind.node.ObjectNode objectNode = dataNode.putObject("object");
            objectNode.put("payment_intent", pspRef);
            objectNode.put("status", "needs_response");
            objectNode.put("reason", "product_not_received");

            processor.processEvent(eventId, "charge.dispute.updated", pspRef, objectNode, rootNode.toString());

            verify(outboxService).publish(
                eq("Payment"),
                eq(disputed.getId().toString()),
                eq("PaymentDisputeUpdated"),
                any()
            );
        }
    }

    // =========================================================================
    // Part 4: Webhook error messages
    // =========================================================================

    @Nested
    @DisplayName("Webhook error messages")
    class WebhookErrorMessages {

        @Test
        @DisplayName("signature failure returns generic Bad Request")
        void webhookError_signatureFailure_genericMessage() {
            WebhookController controller = new WebhookController(signatureVerifier, webhookEventHandler);

            when(signatureVerifier.verify(anyString(), anyString())).thenReturn(false);

            ResponseEntity<String> response = controller.handleWebhook("{}", "bad-sig");

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody()).isEqualTo("Bad Request");
            assertThat(response.getBody()).doesNotContain("signature", "Signature", "HMAC", "invalid", "Invalid");
        }

        @Test
        @DisplayName("processing failure returns generic Internal Server Error")
        void webhookError_processingFailure_genericMessage() throws Exception {
            WebhookController controller = new WebhookController(signatureVerifier, webhookEventHandler);

            when(signatureVerifier.verify(anyString(), anyString())).thenReturn(true);
            org.mockito.Mockito.doThrow(new RuntimeException("DB connection lost"))
                .when(webhookEventHandler).handle(anyString());

            ResponseEntity<String> response = controller.handleWebhook("{}", "valid-sig");

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody()).isEqualTo("Internal Server Error");
            assertThat(response.getBody()).doesNotContain("Processing", "failed", "DB", "connection");
        }
    }
}
