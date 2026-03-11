package com.instacommerce.payment.service;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.repository.LedgerBalanceSummary;
import com.instacommerce.payment.repository.LedgerEntryRepository;
import com.instacommerce.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Read-only verification job that checks ledger integrity for recent payments.
 * <p>
 * For each payment with recent ledger activity the job verifies:
 * <ol>
 *   <li>Total debits == total credits (double-entry balance)</li>
 *   <li>Refund ledger totals align with {@code payment.refundedCents}</li>
 *   <li>Capture ledger totals align with {@code payment.capturedCents}</li>
 *   <li>FAILED payments have no unreleased authorization holds</li>
 *   <li>Captured terminal payments ({@code CAPTURED}, {@code PARTIALLY_REFUNDED},
 *       {@code REFUNDED}) with partial capture have released authorization residue
 *       via {@code PARTIAL_CAPTURE_RELEASE}</li>
 *   <li>VOIDED payments have no unreleased authorization holds
 *       (VOID credits match AUTHORIZATION debits)</li>
 * </ol>
 * <p>
 * This job never mutates data. It is feature-flagged behind
 * {@code payment.verification.ledger-integrity-enabled=true} and uses ShedLock
 * to guarantee single-node execution.
 */
@Component
@ConditionalOnProperty(prefix = "payment.verification", name = "ledger-integrity-enabled", havingValue = "true")
public class LedgerIntegrityVerificationJob {

    private static final Logger log = LoggerFactory.getLogger(LedgerIntegrityVerificationJob.class);

    private static final String REF_TYPE_AUTHORIZATION = "AUTHORIZATION";
    private static final String REF_TYPE_CAPTURE = "CAPTURE";
    private static final String REF_TYPE_FAILURE_RELEASE = "FAILURE_RELEASE";
    private static final String REF_TYPE_REFUND = "REFUND";
    private static final String REF_TYPE_PARTIAL_CAPTURE_RELEASE = "PARTIAL_CAPTURE_RELEASE";
    private static final String REF_TYPE_VOID = "VOID";
    private static final String ENTRY_TYPE_DEBIT = "DEBIT";
    private static final String ENTRY_TYPE_CREDIT = "CREDIT";

    private static final Set<PaymentStatus> CAPTURED_TERMINAL_STATES = EnumSet.of(
        PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED);

    private final LedgerEntryRepository ledgerEntryRepository;
    private final PaymentRepository paymentRepository;
    private final MeterRegistry meterRegistry;
    private final int lookbackMinutes;
    private final int batchSize;

    public LedgerIntegrityVerificationJob(
            LedgerEntryRepository ledgerEntryRepository,
            PaymentRepository paymentRepository,
            MeterRegistry meterRegistry,
            @Value("${payment.verification.ledger-integrity-lookback-minutes:60}") int lookbackMinutes,
            @Value("${payment.verification.ledger-integrity-batch-size:100}") int batchSize) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.paymentRepository = paymentRepository;
        this.meterRegistry = meterRegistry;
        this.lookbackMinutes = lookbackMinutes;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${payment.verification.ledger-integrity-cron:0 */15 * * * *}")
    @SchedulerLock(name = "ledgerIntegrityVerification", lockAtLeastFor = "PT2M", lockAtMostFor = "PT15M")
    public void verifyLedgerIntegrity() {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            doVerify();
        } finally {
            sample.stop(meterRegistry.timer("ledger.verification.duration"));
        }
    }

    private void doVerify() {
        Instant since = Instant.now().minusSeconds(lookbackMinutes * 60L);
        int totalChecked = 0;
        int totalDrift = 0;
        int pageNumber = 0;
        // Safety cap to prevent runaway execution (e.g., 50 pages × 100 = 5000 payments max)
        int maxPages = 50;

        while (pageNumber < maxPages) {
            List<UUID> paymentIds = ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(
                since, PageRequest.of(pageNumber, batchSize));

            if (paymentIds.isEmpty()) {
                break;
            }

            if (pageNumber == 0) {
                log.info("Ledger integrity verification: starting verification for ledger activity since {}", since);
            }

            for (UUID paymentId : paymentIds) {
                try {
                    if (verifyPayment(paymentId)) {
                        totalDrift++;
                    }
                } catch (Exception ex) {
                    counter("ledger.verification.errors", "EXCEPTION").increment();
                    log.error("Ledger integrity verification: unexpected error for paymentId={}",
                        paymentId, ex);
                }
            }

            totalChecked += paymentIds.size();

            // If we got fewer results than the batch size, there are no more pages
            if (paymentIds.size() < batchSize) {
                break;
            }

            pageNumber++;
        }

        if (totalChecked == 0) {
            return;
        }

        counter("ledger.verification.checked", "total").increment(totalChecked);

        if (totalDrift > 0) {
            log.warn("Ledger integrity verification: drift detected in {} of {} payment(s) across {} page(s)",
                totalDrift, totalChecked, pageNumber + 1);
        } else {
            log.info("Ledger integrity verification: {} payment(s) checked across {} page(s), no drift detected",
                totalChecked, pageNumber + 1);
        }

        if (pageNumber >= maxPages) {
            log.warn("Ledger integrity verification: hit max page cap ({} pages, {} payments checked). "
                + "Some payments may be unverified. Consider increasing batch size or frequency.",
                maxPages, totalChecked);
            counter("ledger.verification.errors", "MAX_PAGES_REACHED").increment();
        }
    }

    /**
     * Verifies ledger integrity for a single payment.
     *
     * @return {@code true} if any drift was detected, {@code false} otherwise
     */
    private boolean verifyPayment(UUID paymentId) {
        Optional<Payment> paymentOpt = paymentRepository.findById(paymentId);
        if (paymentOpt.isEmpty()) {
            log.warn("Ledger integrity verification: payment not found for paymentId={}", paymentId);
            counter("ledger.verification.errors", "PAYMENT_NOT_FOUND").increment();
            return false;
        }
        Payment payment = paymentOpt.get();

        List<LedgerBalanceSummary> summaries = ledgerEntryRepository.sumByPaymentIdGrouped(paymentId);

        boolean driftDetected = false;

        // Rule 1: total debits == total credits (double-entry balance)
        long totalDebits = sumByEntryType(summaries, ENTRY_TYPE_DEBIT);
        long totalCredits = sumByEntryType(summaries, ENTRY_TYPE_CREDIT);
        if (totalDebits != totalCredits) {
            counter("ledger.verification.drift", "DEBIT_CREDIT_MISMATCH").increment();
            log.warn("Ledger integrity verification: debit/credit mismatch for paymentId={} "
                    + "(totalDebits={}¢, totalCredits={}¢)",
                paymentId, totalDebits, totalCredits);
            driftDetected = true;
        }

        // Rule 2: refund ledger totals align with payment.refundedCents
        long ledgerRefundCredits = sumByRefTypeAndEntryType(summaries, REF_TYPE_REFUND, ENTRY_TYPE_CREDIT);
        if (ledgerRefundCredits != payment.getRefundedCents()) {
            counter("ledger.verification.drift", "REFUND_MISMATCH").increment();
            log.warn("Ledger integrity verification: refund mismatch for paymentId={} "
                    + "(ledgerRefundCredits={}¢, payment.refundedCents={}¢)",
                paymentId, ledgerRefundCredits, payment.getRefundedCents());
            driftDetected = true;
        }

        // Rule 3: capture ledger totals align with payment.capturedCents
        long ledgerCaptureCredits = sumByRefTypeAndEntryType(summaries, REF_TYPE_CAPTURE, ENTRY_TYPE_CREDIT);
        if (ledgerCaptureCredits != payment.getCapturedCents()) {
            counter("ledger.verification.drift", "CAPTURE_MISMATCH").increment();
            log.warn("Ledger integrity verification: capture mismatch for paymentId={} "
                    + "(ledgerCaptureCredits={}¢, payment.capturedCents={}¢)",
                paymentId, ledgerCaptureCredits, payment.getCapturedCents());
            driftDetected = true;
        }

        // Rule 4: FAILED payments should not have unreleased authorization holds
        if (payment.getStatus() == PaymentStatus.FAILED) {
            long authDebit = sumByRefTypeAndEntryType(summaries, REF_TYPE_AUTHORIZATION, ENTRY_TYPE_DEBIT);
            long releaseCredit = sumByRefTypeAndEntryType(summaries, REF_TYPE_FAILURE_RELEASE, ENTRY_TYPE_CREDIT)
                + sumByRefTypeAndEntryType(summaries, REF_TYPE_VOID, ENTRY_TYPE_CREDIT);
            if (authDebit > 0 && releaseCredit < authDebit) {
                counter("ledger.verification.drift", "FAILED_AUTH_HOLD_UNRELEASED").increment();
                log.warn("Ledger integrity verification: FAILED payment has unreleased authorization hold "
                        + "for paymentId={} (authDebit={}¢, releaseCredit={}¢, unreleasedHold={}¢)",
                    paymentId, authDebit, releaseCredit, authDebit - releaseCredit);
                driftDetected = true;
            }
        }

        // Rule 5: Captured terminal payments with partial capture should have released auth residue
        if (CAPTURED_TERMINAL_STATES.contains(payment.getStatus())) {
            long authResidue = payment.getAmountCents() - payment.getCapturedCents();
            if (authResidue > 0) {
                long partialCaptureReleaseCredit = sumByRefTypeAndEntryType(
                    summaries, REF_TYPE_PARTIAL_CAPTURE_RELEASE, ENTRY_TYPE_CREDIT);
                if (partialCaptureReleaseCredit < authResidue) {
                    long unreleased = authResidue - partialCaptureReleaseCredit;
                    counter("ledger.verification.drift", "PARTIAL_CAPTURE_AUTH_RESIDUE_UNRELEASED").increment();
                    log.warn("Ledger integrity verification: captured terminal payment has unreleased "
                            + "authorization residue after partial capture for paymentId={} "
                            + "(amountCents={}¢, capturedCents={}¢, authResidue={}¢, "
                            + "partialCaptureReleaseCredit={}¢, unreleasedResidue={}¢)",
                        paymentId, payment.getAmountCents(), payment.getCapturedCents(),
                        authResidue, partialCaptureReleaseCredit, unreleased);
                    driftDetected = true;
                }
            }
        }

        // Rule 6: VOIDED payments should not have unreleased authorization holds
        if (payment.getStatus() == PaymentStatus.VOIDED) {
            long authDebit = sumByRefTypeAndEntryType(summaries, REF_TYPE_AUTHORIZATION, ENTRY_TYPE_DEBIT);
            long voidCredit = sumByRefTypeAndEntryType(summaries, REF_TYPE_VOID, ENTRY_TYPE_CREDIT);
            if (authDebit > 0 && voidCredit < authDebit) {
                counter("ledger.verification.drift", "VOIDED_AUTH_HOLD_UNRELEASED").increment();
                log.warn("Ledger integrity verification: VOIDED payment has unreleased authorization hold "
                        + "for paymentId={} (authDebit={}¢, voidCredit={}¢, unreleasedHold={}¢)",
                    paymentId, authDebit, voidCredit, authDebit - voidCredit);
                driftDetected = true;
            }
        }

        return driftDetected;
    }

    private long sumByEntryType(List<LedgerBalanceSummary> summaries, String entryType) {
        return summaries.stream()
            .filter(s -> entryType.equals(s.getEntryType()))
            .mapToLong(LedgerBalanceSummary::getTotalAmountCents)
            .sum();
    }

    private long sumByRefTypeAndEntryType(List<LedgerBalanceSummary> summaries,
                                          String referenceType, String entryType) {
        return summaries.stream()
            .filter(s -> referenceType.equals(s.getReferenceType())
                         && entryType.equals(s.getEntryType()))
            .mapToLong(LedgerBalanceSummary::getTotalAmountCents)
            .sum();
    }

    private Counter counter(String name, String status) {
        return meterRegistry.counter(name, "status", status);
    }
}
