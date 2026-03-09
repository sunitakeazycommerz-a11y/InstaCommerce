package com.instacommerce.payment.service;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.gateway.GatewayStatusResult;
import com.instacommerce.payment.gateway.PaymentGateway;
import com.instacommerce.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically resolves payments stuck in a pending state beyond a configurable
 * age threshold by polling PSP truth and reconciling local state.
 * <p>
 * This job never reissues gateway mutations — it only reads PSP state and updates
 * the local database to match. Guard-railed behind a feature flag
 * ({@code payment.recovery.stale-pending-enabled=true}) and ShedLock to
 * guarantee single-node execution.
 */
@Component
@ConditionalOnProperty(prefix = "payment.recovery", name = "stale-pending-enabled", havingValue = "true")
public class StalePendingPaymentRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(StalePendingPaymentRecoveryJob.class);

    static final List<PaymentStatus> PENDING_STATUSES = List.of(
        PaymentStatus.AUTHORIZE_PENDING,
        PaymentStatus.CAPTURE_PENDING,
        PaymentStatus.VOID_PENDING
    );

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentTransactionHelper txHelper;
    private final MeterRegistry meterRegistry;
    private final int staleThresholdMinutes;
    private final int batchSize;

    public StalePendingPaymentRecoveryJob(
            PaymentRepository paymentRepository,
            PaymentGateway paymentGateway,
            PaymentTransactionHelper txHelper,
            MeterRegistry meterRegistry,
            @Value("${payment.recovery.stale-threshold-minutes:30}") int staleThresholdMinutes,
            @Value("${payment.recovery.batch-size:50}") int batchSize) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.txHelper = txHelper;
        this.meterRegistry = meterRegistry;
        this.staleThresholdMinutes = staleThresholdMinutes;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${payment.recovery.stale-pending-cron:0 */5 * * * *}")
    @SchedulerLock(name = "stalePendingRecovery", lockAtLeastFor = "PT2M", lockAtMostFor = "PT15M")
    public void recoverStalePendingPayments() {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            doRecover();
        } finally {
            sample.stop(meterRegistry.timer("payment.recovery.duration"));
        }
    }

    private void doRecover() {
        Instant cutoff = Instant.now().minusSeconds(staleThresholdMinutes * 60L);
        List<Payment> stalePayments = paymentRepository.findStalePendingPayments(
            PENDING_STATUSES, cutoff, PageRequest.ofSize(batchSize));

        if (stalePayments.isEmpty()) {
            return;
        }

        log.info("Recovery: found {} stale pending payment(s) older than {}", stalePayments.size(), cutoff);
        counter("payment.recovery.found", "total").increment(stalePayments.size());

        for (Payment payment : stalePayments) {
            try {
                RecoveryOutcome outcome = recoverPayment(payment);
                counter("payment.recovery.resolved",
                    payment.getStatus().name(), outcome.name()).increment();
                log.info("Recovery: resolved payment {} (was={}, outcome={})",
                    payment.getId(), payment.getStatus(), outcome);
            } catch (Exception ex) {
                counter("payment.recovery.errors",
                    payment.getStatus().name(), "EXCEPTION").increment();
                log.error("Recovery: failed to resolve payment {} (status={})",
                    payment.getId(), payment.getStatus(), ex);
            }
        }
    }

    RecoveryOutcome recoverPayment(Payment payment) {
        return switch (payment.getStatus()) {
            case AUTHORIZE_PENDING -> recoverAuthorizePending(payment);
            case CAPTURE_PENDING -> recoverCapturePending(payment);
            case VOID_PENDING -> recoverVoidPending(payment);
            default -> RecoveryOutcome.SKIPPED;
        };
    }

    private RecoveryOutcome recoverAuthorizePending(Payment payment) {
        if (payment.getPspReference() == null || payment.getPspReference().isBlank()) {
            txHelper.resolveStaleAuthorizationFailed(payment.getId(),
                "no_psp_reference_after_threshold");
            return RecoveryOutcome.MARKED_FAILED;
        }

        GatewayStatusResult pspState = paymentGateway.getStatus(payment.getPspReference());

        if (pspState.isInFlight()) {
            log.debug("Recovery: payment {} still processing at PSP, skipping", payment.getId());
            return RecoveryOutcome.SKIPPED;
        }
        if (pspState.isAuthorized()) {
            txHelper.completeAuthorization(payment.getId(), payment.getPspReference());
            return RecoveryOutcome.COMPLETED_FORWARD;
        }
        if (pspState.isCaptured()) {
            // PSP already captured — reconcile directly to CAPTURED in one transaction
            long capturedCents = resolveCapturedAmount(pspState, payment);
            txHelper.reconcileDirectToCaptured(payment.getId(), payment.getPspReference(), capturedCents);
            return RecoveryOutcome.COMPLETED_FORWARD;
        }

        // Terminal failure or unrecoverable states
        txHelper.resolveStaleAuthorizationFailed(payment.getId(),
            "psp_status_" + pspState.rawStatus());
        return RecoveryOutcome.MARKED_FAILED;
    }

    private RecoveryOutcome recoverCapturePending(Payment payment) {
        if (payment.getPspReference() == null || payment.getPspReference().isBlank()) {
            txHelper.resolveStaleCaptureFailed(payment.getId(),
                "no_psp_reference_after_threshold");
            return RecoveryOutcome.REVERTED;
        }

        GatewayStatusResult pspState = paymentGateway.getStatus(payment.getPspReference());

        if (pspState.isInFlight()) {
            log.debug("Recovery: payment {} capture still processing at PSP, skipping", payment.getId());
            return RecoveryOutcome.SKIPPED;
        }
        if (pspState.isCaptured()) {
            long capturedCents = resolveCapturedAmount(pspState, payment);
            txHelper.completeCaptured(payment.getId(), capturedCents);
            return RecoveryOutcome.COMPLETED_FORWARD;
        }
        if (pspState.isAuthorized()) {
            txHelper.resolveStaleCaptureFailed(payment.getId(),
                "psp_still_authorized_capture_not_applied");
            return RecoveryOutcome.REVERTED;
        }
        if (pspState.isCanceled()) {
            txHelper.resolveStaleCaptureFailed(payment.getId(),
                "psp_canceled_during_capture");
            return RecoveryOutcome.REVERTED;
        }

        log.warn("Recovery: payment {} in CAPTURE_PENDING but PSP state is {}, skipping",
            payment.getId(), pspState.rawStatus());
        return RecoveryOutcome.SKIPPED;
    }

    private RecoveryOutcome recoverVoidPending(Payment payment) {
        if (payment.getPspReference() == null || payment.getPspReference().isBlank()) {
            txHelper.resolveStaleVoidFailed(payment.getId(),
                "no_psp_reference_after_threshold");
            return RecoveryOutcome.REVERTED;
        }

        GatewayStatusResult pspState = paymentGateway.getStatus(payment.getPspReference());

        if (pspState.isInFlight()) {
            log.debug("Recovery: payment {} void still processing at PSP, skipping", payment.getId());
            return RecoveryOutcome.SKIPPED;
        }
        if (pspState.isCanceled()) {
            txHelper.completeVoided(payment.getId());
            return RecoveryOutcome.COMPLETED_FORWARD;
        }
        if (pspState.isAuthorized()) {
            txHelper.resolveStaleVoidFailed(payment.getId(),
                "psp_still_authorized_void_not_applied");
            return RecoveryOutcome.REVERTED;
        }
        if (pspState.isCaptured()) {
            // PSP already captured while we tried to void — unusual edge case
            txHelper.resolveStaleVoidFailed(payment.getId(),
                "psp_captured_during_void");
            return RecoveryOutcome.REVERTED;
        }

        log.warn("Recovery: payment {} in VOID_PENDING but PSP state is {}, skipping",
            payment.getId(), pspState.rawStatus());
        return RecoveryOutcome.SKIPPED;
    }

    private long resolveCapturedAmount(GatewayStatusResult pspState, Payment payment) {
        if (pspState.amountCapturedCents() != null && pspState.amountCapturedCents() > 0) {
            return pspState.amountCapturedCents();
        }
        return payment.getAmountCents();
    }

    private Counter counter(String name, String status) {
        return meterRegistry.counter(name, "status", status);
    }

    private Counter counter(String name, String status, String outcome) {
        return meterRegistry.counter(name, "status", status, "outcome", outcome);
    }

    enum RecoveryOutcome {
        COMPLETED_FORWARD,
        REVERTED,
        MARKED_FAILED,
        SKIPPED
    }
}
