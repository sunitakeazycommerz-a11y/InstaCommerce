package com.instacommerce.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.instacommerce.payment.PaymentServiceApplication;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.domain.model.RefundStatus;
import com.instacommerce.payment.dto.request.RefundRequest;
import com.instacommerce.payment.dto.response.RefundResponse;
import com.instacommerce.payment.gateway.GatewayRefundResult;
import com.instacommerce.payment.gateway.PaymentGateway;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.RefundRepository;
import com.instacommerce.payment.service.RefundService;
import com.instacommerce.payment.service.RefundTransactionHelper;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    classes = PaymentServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class RefundIntegrationTest {

    private static final String TEST_PUBLIC_KEY = """
        -----BEGIN PUBLIC KEY-----
        MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwLQ9ItN2Ynh6wOZ43vBR
        +mwBiITyLugZCLnF20z7D/g17KhgeM3D6JfexegX/Gtekt+Oha2/1KcSAwRwsDZZ
        7CU8gYFWb+TKwrWTJd8efMlhUxeTQvIS3DlOxw5QuWh6wB5FsOmMchqehTGtOcB6
        VQ6yqNwfq4RZtb8LdK0mOyGGSZT8xVCVVL1Z8XNPqveKauyJmedzKTUyssWLdJe3
        QyXUggGR9rdcanfBjapvguHFhoxeXfqW+CxU7ilk1KuNGcj9VDhx92OKugJAjrPY
        FsFZ2LlcpTlxCUPsAOoqdi0mM4dK+HH7mKgMwza36AipDi1tWhlzFMqTRG9MjZw8
        WQIDAQAB
        -----END PUBLIC KEY-----
        """;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("payment.jwt.public-key", () -> TEST_PUBLIC_KEY);
        registry.add("payment.choreography.order-cancelled-consumer-enabled", () -> "false");
        registry.add("payment.webhook.refund-outbox-enabled", () -> "false");
        registry.add("stripe.api-key", () -> "test-stripe-key");
        registry.add("stripe.webhook-secret", () -> "test-webhook-secret");
        registry.add("internal.service.token", () -> "test-internal-service-token");
    }

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private RefundService refundService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private RefundTransactionHelper refundTransactionHelper;

    @MockitoBean
    private PaymentGateway paymentGateway;

    @BeforeEach
    void cleanDatabase() {
        reset(paymentGateway, refundTransactionHelper);
        jdbcTemplate.execute("""
            TRUNCATE TABLE
                audit_log,
                ledger_entries,
                outbox_events,
                processed_webhook_events,
                refunds,
                payments,
                shedlock
            RESTART IDENTITY CASCADE
            """);
    }

    @AfterEach
    void clearMocks() {
        reset(paymentGateway, refundTransactionHelper);
    }

    @Test
    void enforcesUniquePspRefundIdsAtDatabaseLevel() {
        Payment payment = saveCapturedPayment(10_000L);

        refundRepository.saveAndFlush(newRefund(payment.getId(), "key-one", "psp-refund-1", 1_000L));

        assertThatThrownBy(() ->
            refundRepository.saveAndFlush(newRefund(payment.getId(), "key-two", "psp-refund-1", 1_500L)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentDuplicateRefundRequestsReturnSameRefund() throws Exception {
        Payment payment = saveCapturedPayment(10_000L);
        RefundRequest request = new RefundRequest(1_000L, "customer_request", "duplicate-refund-key");
        CyclicBarrier barrier = new CyclicBarrier(2);

        doAnswer(invocation -> {
            await(barrier);
            return invocation.callRealMethod();
        }).when(refundTransactionHelper).savePendingRefund(eq(payment.getId()), any(RefundRequest.class),
            eq("duplicate-refund-key"));

        when(paymentGateway.refund(eq(payment.getPspReference()), eq(1_000L), eq("duplicate-refund-key"),
            any(UUID.class))).thenReturn(GatewayRefundResult.success("psp-refund-123"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<RefundResponse> task = () -> refundService.refund(payment.getId(), request);
            Future<RefundResponse> first = executor.submit(task);
            Future<RefundResponse> second = executor.submit(task);

            RefundResponse firstResponse = assertDoesNotThrow(() -> first.get(10, TimeUnit.SECONDS));
            RefundResponse secondResponse = assertDoesNotThrow(() -> second.get(10, TimeUnit.SECONDS));

            assertThat(secondResponse.refundId()).isEqualTo(firstResponse.refundId());
            assertThat(refundRepository.findByPaymentId(payment.getId())).hasSize(1);

            verify(paymentGateway, times(1)).refund(eq(payment.getPspReference()), eq(1_000L),
                eq("duplicate-refund-key"), any(UUID.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void staleRefundEntityTriggersOptimisticLockFailure() {
        Payment payment = saveCapturedPayment(10_000L);

        // Persist a PENDING refund (version = 0)
        Refund refund = new Refund();
        refund.setPaymentId(payment.getId());
        refund.setAmountCents(2_000L);
        refund.setReason("test-occ");
        refund.setIdempotencyKey("occ-key-" + UUID.randomUUID());
        refund.setStatus(RefundStatus.PENDING);
        Refund saved = refundRepository.saveAndFlush(refund);

        // Read the entity into the persistence context (version = 0)
        Refund stale = refundRepository.findById(saved.getId()).orElseThrow();
        assertThat(stale.getVersion()).isEqualTo(0L);

        // Simulate a concurrent writer bumping the version via raw SQL
        jdbcTemplate.update(
            "UPDATE refunds SET version = version + 1, status = 'COMPLETED' WHERE id = ?",
            saved.getId());

        // The stale entity still thinks version = 0, but the DB row is now version = 1
        stale.setStatus(RefundStatus.FAILED);

        assertThatThrownBy(() -> refundRepository.saveAndFlush(stale))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // Verify the concurrent writer's state won (COMPLETED, version = 1)
        Refund winner = jdbcTemplate.queryForObject(
            "SELECT status, version FROM refunds WHERE id = ?",
            (rs, rowNum) -> {
                Refund r = new Refund();
                r.setStatus(RefundStatus.valueOf(rs.getString("status")));
                r.setVersion(rs.getLong("version"));
                return r;
            },
            saved.getId());
        assertThat(winner).isNotNull();
        assertThat(winner.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(winner.getVersion()).isEqualTo(1L);
    }

    private Payment saveCapturedPayment(long capturedCents) {
        Payment payment = new Payment();
        payment.setOrderId(UUID.randomUUID());
        payment.setAmountCents(capturedCents);
        payment.setCapturedCents(capturedCents);
        payment.setRefundedCents(0L);
        payment.setCurrency("INR");
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setPspReference("psp-ref-" + UUID.randomUUID());
        payment.setIdempotencyKey("payment-key-" + UUID.randomUUID());
        payment.setPaymentMethod("card");
        return paymentRepository.saveAndFlush(payment);
    }

    private Refund newRefund(UUID paymentId, String idempotencyKey, String pspRefundId, long amountCents) {
        Refund refund = new Refund();
        refund.setPaymentId(paymentId);
        refund.setAmountCents(amountCents);
        refund.setReason("test");
        refund.setIdempotencyKey(idempotencyKey);
        refund.setPspRefundId(pspRefundId);
        refund.setStatus(RefundStatus.COMPLETED);
        return refund;
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("Timed out coordinating concurrent refund requests", ex);
        }
    }
}
