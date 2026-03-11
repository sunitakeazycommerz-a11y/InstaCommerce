package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.domain.model.RefundStatus;
import com.instacommerce.payment.dto.request.RefundRequest;
import com.instacommerce.payment.dto.response.RefundResponse;
import com.instacommerce.payment.gateway.PaymentGateway;
import com.instacommerce.payment.repository.RefundRepository;
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
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class RefundServiceDuplicateKeyTest {

    @Mock RefundRepository refundRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock RefundTransactionHelper txHelper;

    RefundService service;

    @BeforeEach
    void setUp() {
        service = new RefundService(refundRepository, paymentGateway, txHelper,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private Refund existingRefund(UUID paymentId, String idempotencyKey) {
        Refund r = new Refund();
        r.setId(UUID.randomUUID());
        r.setPaymentId(paymentId);
        r.setAmountCents(5000);
        r.setIdempotencyKey(idempotencyKey);
        r.setStatus(RefundStatus.PENDING);
        r.setCreatedAt(Instant.now());
        return r;
    }

    @Nested
    @DisplayName("Duplicate idempotency-key race on insert")
    class DuplicateKeyRace {

        @Test
        @DisplayName("Concurrent insert race returns the race-winner refund instead of 500")
        void raceWinnerReturnedOnDuplicateKey() {
            UUID paymentId = UUID.randomUUID();
            String key = "dedup-key-abc";
            RefundRequest request = new RefundRequest(5000, "test", key);
            Refund winner = existingRefund(paymentId, key);

            // First lookup misses (race window)
            when(refundRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty(), Optional.of(winner));
            when(txHelper.savePendingRefund(eq(paymentId), eq(request), eq(key)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

            RefundResponse response = service.refund(paymentId, request);

            assertThat(response.refundId()).isEqualTo(winner.getId());
            assertThat(response.amountCents()).isEqualTo(5000);
            // Gateway should never be called for a duplicate
            verify(paymentGateway, never()).refund(any(), any(long.class), any(), any());
        }

        @Test
        @DisplayName("Non-idempotency constraint violation propagates unchanged")
        void nonIdempotencyViolationPropagates() {
            UUID paymentId = UUID.randomUUID();
            String key = "key-xyz";
            RefundRequest request = new RefundRequest(3000, null, key);

            when(refundRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
            DataIntegrityViolationException dive =
                new DataIntegrityViolationException("some other constraint");
            when(txHelper.savePendingRefund(eq(paymentId), eq(request), eq(key))).thenThrow(dive);

            assertThatThrownBy(() -> service.refund(paymentId, request))
                .isSameAs(dive);
        }
    }
}
