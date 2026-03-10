package com.instacommerce.payment.service;

import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.domain.model.RefundStatus;
import com.instacommerce.payment.gateway.GatewayRefundStatusResult;
import com.instacommerce.payment.gateway.PaymentGateway;
import com.instacommerce.payment.repository.RefundRepository;
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
 * Periodically resolves refunds stuck in PENDING state beyond a configurable
 * age threshold by polling PSP truth and reconciling local state.
 * <p>
 * This job never reissues gateway mutations — it only reads PSP refund state
 * and updates the local database to match. Guard-railed behind a feature flag
 * ({@code payment.recovery.stale-pending-refund-enabled=true}) and ShedLock to
 * guarantee single-node execution.
 */
@Component
@ConditionalOnProperty(prefix = "payment.recovery", name = "stale-pending-refund-enabled", havingValue = "true")
public class StalePendingRefundRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(StalePendingRefundRecoveryJob.class);

    static final RefundStatus RECOVERY_STATUS = RefundStatus.PENDING;

    private final RefundRepository refundRepository;
    private final PaymentGateway paymentGateway;
    private final RefundTransactionHelper txHelper;
    private final MeterRegistry meterRegistry;
    private final int staleThresholdMinutes;
    private final int batchSize;

    public StalePendingRefundRecoveryJob(
            RefundRepository refundRepository,
            PaymentGateway paymentGateway,
            RefundTransactionHelper txHelper,
            MeterRegistry meterRegistry,
            @Value("${payment.recovery.stale-pending-refund-threshold-minutes:60}") int staleThresholdMinutes,
            @Value("${payment.recovery.stale-pending-refund-batch-size:50}") int batchSize) {
        this.refundRepository = refundRepository;
        this.paymentGateway = paymentGateway;
        this.txHelper = txHelper;
        this.meterRegistry = meterRegistry;
        this.staleThresholdMinutes = staleThresholdMinutes;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${payment.recovery.stale-pending-refund-cron:0 */10 * * * *}")
    @SchedulerLock(name = "stalePendingRefundRecovery", lockAtLeastFor = "PT2M", lockAtMostFor = "PT15M")
    public void recoverStalePendingRefunds() {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            doRecover();
        } finally {
            sample.stop(meterRegistry.timer("refund.recovery.duration"));
        }
    }

    private void doRecover() {
        Instant cutoff = Instant.now().minusSeconds(staleThresholdMinutes * 60L);
        List<Refund> staleRefunds = refundRepository.findStalePendingRefunds(
            RECOVERY_STATUS, cutoff, PageRequest.ofSize(batchSize));

        if (staleRefunds.isEmpty()) {
            return;
        }

        log.info("Refund recovery: found {} stale pending refund(s) older than {}",
            staleRefunds.size(), cutoff);
        counter("refund.recovery.found", "total").increment(staleRefunds.size());

        for (Refund refund : staleRefunds) {
            try {
                RecoveryOutcome outcome = recoverRefund(refund);
                counter("refund.recovery.resolved",
                    refund.getStatus().name(), outcome.name()).increment();
                log.info("Refund recovery: resolved refund {} (paymentId={}, outcome={})",
                    refund.getId(), refund.getPaymentId(), outcome);
            } catch (Exception ex) {
                counter("refund.recovery.errors",
                    refund.getStatus().name(), "EXCEPTION").increment();
                log.error("Refund recovery: failed to resolve refund {} (paymentId={})",
                    refund.getId(), refund.getPaymentId(), ex);
            }
        }
    }

    RecoveryOutcome recoverRefund(Refund refund) {
        // No PSP refund ID means the refund never reached the PSP — mark failed directly
        if (refund.getPspRefundId() == null || refund.getPspRefundId().isBlank()) {
            txHelper.resolveStaleRefundFailed(refund.getId(),
                "no_psp_refund_id_after_threshold");
            return RecoveryOutcome.MARKED_FAILED;
        }

        GatewayRefundStatusResult pspState = paymentGateway.getRefundStatus(refund.getPspRefundId());

        if (pspState.isSucceeded()) {
            txHelper.completeStaleRefund(refund.getId());
            return RecoveryOutcome.COMPLETED_FORWARD;
        }

        if (pspState.isFailed() || pspState.isCanceled()) {
            txHelper.resolveStaleRefundFailed(refund.getId(),
                "psp_status_" + pspState.rawStatus());
            return RecoveryOutcome.MARKED_FAILED;
        }

        if (pspState.isPending()) {
            log.debug("Refund recovery: refund {} still pending at PSP, skipping",
                refund.getId());
            return RecoveryOutcome.SKIPPED;
        }

        // REQUIRES_ACTION, UNKNOWN, or unexpected states — skip to avoid wrong resolution
        log.warn("Refund recovery: refund {} has unresolvable PSP state {}, skipping",
            refund.getId(), pspState.rawStatus());
        return RecoveryOutcome.SKIPPED;
    }

    private Counter counter(String name, String status) {
        return meterRegistry.counter(name, "status", status);
    }

    private Counter counter(String name, String status, String outcome) {
        return meterRegistry.counter(name, "status", status, "outcome", outcome);
    }

    enum RecoveryOutcome {
        COMPLETED_FORWARD,
        MARKED_FAILED,
        SKIPPED
    }
}
