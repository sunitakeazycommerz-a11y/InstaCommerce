package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.payment.domain.model.AuditLog;
import com.instacommerce.payment.exception.TraceIdProvider;
import com.instacommerce.payment.repository.AuditLogRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
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
@DisplayName("AuditLogService – transaction isolation")
class AuditLogServiceIsolationTest {

    @Mock AuditLogRepository auditLogRepository;
    @Mock TraceIdProvider traceIdProvider;
    @Mock AuditLogService selfProxy;

    SimpleMeterRegistry meterRegistry;
    AuditLogService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new AuditLogService(auditLogRepository, traceIdProvider, meterRegistry, selfProxy);
    }

    // ── Failure isolation via logSafely ─────────────────────────────────

    @Nested
    @DisplayName("When audit persistence fails")
    class AuditFailureIsolation {

        @Test
        @DisplayName("logSafely catches DataIntegrityViolationException from proxy — caller's transaction is safe")
        void auditFailureDoesNotPropagate() {
            doThrow(new DataIntegrityViolationException("duplicate key"))
                    .when(selfProxy).log(any(), any(), any(), any(), any());

            assertThatCode(() ->
                    service.logSafely(UUID.randomUUID(), "PAYMENT_CREATED", "Payment", "pay-1", Map.of())
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Failure metric audit.log.failure is incremented on exception")
        void failureMetricIsIncremented() {
            doThrow(new DataIntegrityViolationException("duplicate key"))
                    .when(selfProxy).log(any(), any(), any(), any(), any());

            service.logSafely(UUID.randomUUID(), "REFUND_CREATED", "Refund", "ref-1", Map.of());

            double count = meterRegistry.counter("audit.log.failure").count();
            assertThat(count).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Arbitrary RuntimeException is also caught by logSafely")
        void arbitraryRuntimeExceptionSwallowed() {
            doThrow(new RuntimeException("unexpected DB error"))
                    .when(selfProxy).log(any(), any(), any(), any(), any());

            assertThatCode(() ->
                    service.logSafely(null, "CAPTURE", "Payment", "pay-2", Map.of("amount", 100))
            ).doesNotThrowAnyException();

            assertThat(meterRegistry.counter("audit.log.failure").count()).isEqualTo(1.0);
        }
    }

    // ── log() propagates exceptions (no internal catch) ────────────────

    @Nested
    @DisplayName("log() propagates exceptions (callers must use logSafely)")
    class LogPropagatesExceptions {

        @Test
        @DisplayName("log() throws DataIntegrityViolationException to caller")
        void logThrowsOnPersistenceFailure() {
            when(auditLogRepository.save(any(AuditLog.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate key"));

            assertThatThrownBy(() ->
                    service.log(UUID.randomUUID(), "PAYMENT_CREATED", "Payment", "pay-1", Map.of())
            ).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // ── Happy path ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("When audit persistence succeeds")
    class NormalAuditPath {

        @Test
        @DisplayName("Audit entry is saved via repository")
        void auditEntryIsPersisted() {
            when(auditLogRepository.save(any(AuditLog.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            service.log(UUID.randomUUID(), "PAYMENT_CREATED", "Payment", "pay-3", Map.of("gateway", "stripe"));

            verify(auditLogRepository).save(any(AuditLog.class));
        }

        @Test
        @DisplayName("No failure metric incremented on success")
        void noFailureMetricOnSuccess() {
            when(auditLogRepository.save(any(AuditLog.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            service.log(UUID.randomUUID(), "PAYMENT_CREATED", "Payment", "pay-4", Map.of());

            assertThat(meterRegistry.counter("audit.log.failure").count()).isEqualTo(0.0);
        }
    }
}
