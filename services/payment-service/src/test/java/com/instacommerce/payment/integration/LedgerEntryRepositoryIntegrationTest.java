package com.instacommerce.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.instacommerce.payment.PaymentServiceApplication;
import com.instacommerce.payment.domain.model.LedgerEntry;
import com.instacommerce.payment.domain.model.LedgerEntryType;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.gateway.PaymentGateway;
import com.instacommerce.payment.repository.LedgerBalanceSummary;
import com.instacommerce.payment.repository.LedgerEntryRepository;
import com.instacommerce.payment.repository.PaymentRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests proving the Wave-9 JPQL queries in {@link LedgerEntryRepository}
 * work against a real PostgreSQL instance with the native {@code ledger_entry_type} enum.
 */
@SpringBootTest(
    classes = PaymentServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class LedgerEntryRepositoryIntegrationTest {

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
    }

    @MockitoBean
    private PaymentGateway paymentGateway;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID paymentIdA;
    private UUID paymentIdB;

    @BeforeEach
    void cleanAndSeed() {
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

        Payment paymentA = saveCapturedPayment();
        Payment paymentB = saveCapturedPayment();
        paymentIdA = paymentA.getId();
        paymentIdB = paymentB.getId();

        // Payment A: CAPTURE DEBIT 5000, CAPTURE CREDIT 5000, REFUND DEBIT 1000, REFUND CREDIT 1000, FEE DEBIT 200
        saveLedgerEntry(paymentIdA, LedgerEntryType.DEBIT, 5000L, "CAPTURE", "cap-1");
        saveLedgerEntry(paymentIdA, LedgerEntryType.CREDIT, 5000L, "CAPTURE", "cap-1");
        saveLedgerEntry(paymentIdA, LedgerEntryType.DEBIT, 1000L, "REFUND", "ref-1");
        saveLedgerEntry(paymentIdA, LedgerEntryType.CREDIT, 1000L, "REFUND", "ref-1");
        saveLedgerEntry(paymentIdA, LedgerEntryType.DEBIT, 200L, "FEE", "fee-1");

        // Payment B: single CAPTURE pair (used for multi-payment discovery test)
        saveLedgerEntry(paymentIdB, LedgerEntryType.DEBIT, 3000L, "CAPTURE", "cap-b1");
        saveLedgerEntry(paymentIdB, LedgerEntryType.CREDIT, 3000L, "CAPTURE", "cap-b1");
    }

    // ── sumByPaymentIdGrouped ──────────────────────────────────────────

    @Test
    void groupedTotals_returnCorrectSumsPerReferenceTypeAndEntryType() {
        List<LedgerBalanceSummary> summaries =
            ledgerEntryRepository.sumByPaymentIdGrouped(paymentIdA);

        Map<String, Long> index = summaries.stream()
            .collect(Collectors.toMap(
                s -> s.getReferenceType() + ":" + s.getEntryType(),
                LedgerBalanceSummary::getTotalAmountCents));

        assertThat(index).hasSize(5);
        assertThat(index.get("CAPTURE:DEBIT")).isEqualTo(5000L);
        assertThat(index.get("CAPTURE:CREDIT")).isEqualTo(5000L);
        assertThat(index.get("REFUND:DEBIT")).isEqualTo(1000L);
        assertThat(index.get("REFUND:CREDIT")).isEqualTo(1000L);
        assertThat(index.get("FEE:DEBIT")).isEqualTo(200L);
    }

    @Test
    void groupedTotals_entryTypeProjectedAsExpectedStringValues() {
        List<LedgerBalanceSummary> summaries =
            ledgerEntryRepository.sumByPaymentIdGrouped(paymentIdA);

        List<String> entryTypes = summaries.stream()
            .map(LedgerBalanceSummary::getEntryType)
            .distinct()
            .sorted()
            .toList();

        // Verification job filters rely on these exact string values
        assertThat(entryTypes).containsExactly("CREDIT", "DEBIT");
    }

    @Test
    void groupedTotals_emptyForUnknownPayment() {
        List<LedgerBalanceSummary> summaries =
            ledgerEntryRepository.sumByPaymentIdGrouped(UUID.randomUUID());

        assertThat(summaries).isEmpty();
    }

    @Test
    void groupedTotals_isolatedPerPayment() {
        List<LedgerBalanceSummary> summariesB =
            ledgerEntryRepository.sumByPaymentIdGrouped(paymentIdB);

        Map<String, Long> index = summariesB.stream()
            .collect(Collectors.toMap(
                s -> s.getReferenceType() + ":" + s.getEntryType(),
                LedgerBalanceSummary::getTotalAmountCents));

        assertThat(index).hasSize(2);
        assertThat(index.get("CAPTURE:DEBIT")).isEqualTo(3000L);
        assertThat(index.get("CAPTURE:CREDIT")).isEqualTo(3000L);
    }

    @Test
    void groupedTotals_includeAuthorizationAndFailureReleaseReferenceTypes() {
        Payment failedPayment = saveCapturedPayment();
        failedPayment.setStatus(PaymentStatus.FAILED);
        failedPayment.setCapturedCents(0L);
        paymentRepository.saveAndFlush(failedPayment);

        String referenceId = failedPayment.getId().toString();
        saveLedgerEntry(failedPayment.getId(), LedgerEntryType.DEBIT, 10_000L, "AUTHORIZATION", referenceId);
        saveLedgerEntry(failedPayment.getId(), LedgerEntryType.CREDIT, 10_000L, "AUTHORIZATION", referenceId);
        saveLedgerEntry(failedPayment.getId(), LedgerEntryType.DEBIT, 10_000L, "FAILURE_RELEASE", referenceId);
        saveLedgerEntry(failedPayment.getId(), LedgerEntryType.CREDIT, 10_000L, "FAILURE_RELEASE", referenceId);

        List<LedgerBalanceSummary> summaries =
            ledgerEntryRepository.sumByPaymentIdGrouped(failedPayment.getId());

        Map<String, Long> index = summaries.stream()
            .collect(Collectors.toMap(
                s -> s.getReferenceType() + ":" + s.getEntryType(),
                LedgerBalanceSummary::getTotalAmountCents));

        assertThat(index.get("AUTHORIZATION:DEBIT")).isEqualTo(10_000L);
        assertThat(index.get("AUTHORIZATION:CREDIT")).isEqualTo(10_000L);
        assertThat(index.get("FAILURE_RELEASE:DEBIT")).isEqualTo(10_000L);
        assertThat(index.get("FAILURE_RELEASE:CREDIT")).isEqualTo(10_000L);
    }

    // ── findDistinctPaymentIdsWithEntriesSince ─────────────────────────

    @Test
    void recentPaymentDiscovery_returnsDistinctIdsWithinWindow() {
        // All seed entries have createdAt ≈ now(), so a lookback of 1 hour should find both
        Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);

        List<UUID> recentIds = ledgerEntryRepository
            .findDistinctPaymentIdsWithEntriesSince(oneHourAgo, PageRequest.of(0, 100));

        assertThat(recentIds).containsExactlyInAnyOrder(paymentIdA, paymentIdB);
    }

    @Test
    void recentPaymentDiscovery_excludesOlderEntries() {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);

        List<UUID> recentIds = ledgerEntryRepository
            .findDistinctPaymentIdsWithEntriesSince(future, PageRequest.of(0, 100));

        assertThat(recentIds).isEmpty();
    }

    @Test
    void recentPaymentDiscovery_respectsPageSize() {
        Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);

        List<UUID> page = ledgerEntryRepository
            .findDistinctPaymentIdsWithEntriesSince(oneHourAgo, PageRequest.of(0, 1));

        assertThat(page).hasSize(1);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private Payment saveCapturedPayment() {
        Payment payment = new Payment();
        payment.setOrderId(UUID.randomUUID());
        payment.setAmountCents(10_000L);
        payment.setCapturedCents(10_000L);
        payment.setRefundedCents(0L);
        payment.setCurrency("INR");
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setPspReference("psp-ref-" + UUID.randomUUID());
        payment.setIdempotencyKey("pay-key-" + UUID.randomUUID());
        payment.setPaymentMethod("card");
        return paymentRepository.saveAndFlush(payment);
    }

    private LedgerEntry saveLedgerEntry(UUID paymentId, LedgerEntryType entryType,
                                        long amountCents, String referenceType,
                                        String referenceId) {
        LedgerEntry entry = new LedgerEntry();
        entry.setPaymentId(paymentId);
        entry.setEntryType(entryType);
        entry.setAmountCents(amountCents);
        entry.setAccount("merchant");
        entry.setReferenceType(referenceType);
        entry.setReferenceId(referenceId);
        entry.setDescription("test-" + referenceType);
        return ledgerEntryRepository.saveAndFlush(entry);
    }
}
