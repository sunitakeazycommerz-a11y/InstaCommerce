package com.instacommerce.wallet.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instacommerce.wallet.WalletLoyaltyServiceApplication;
import com.instacommerce.wallet.dto.response.LoyaltyResponse;
import com.instacommerce.wallet.exception.ApiException;
import com.instacommerce.wallet.repository.LoyaltyAccountRepository;
import com.instacommerce.wallet.service.LoyaltyService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link LoyaltyService} proving point earning, redemption,
 * insufficient-balance rejection, and duplicate-redemption idempotency against a
 * real PostgreSQL instance with Flyway migrations.
 */
@SpringBootTest(
    classes = WalletLoyaltyServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class LoyaltyServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private LoyaltyService loyaltyService;

    @Autowired
    private LoyaltyAccountRepository loyaltyAccountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
            TRUNCATE TABLE
                outbox_events,
                referral_redemptions,
                referral_codes,
                loyalty_transactions,
                loyalty_accounts,
                wallet_ledger_entries,
                wallet_transactions,
                wallets,
                shedlock
            RESTART IDENTITY CASCADE
            """);
    }

    // -- shouldEarnPoints ---------------------------------------------------------

    @Test
    void shouldEarnPoints() {
        UUID userId = UUID.randomUUID();
        String orderId = "order-earn-" + UUID.randomUUID();

        // 1 point per rupee, order total = 15000 cents = 150 rupees = 150 points
        LoyaltyResponse response = loyaltyService.earnPoints(userId, orderId, 15000L);

        assertThat(response).isNotNull();
        assertThat(response.pointsBalance()).isEqualTo(150);
        assertThat(response.lifetimePoints()).isEqualTo(150);
        assertThat(response.tier()).isEqualTo("BRONZE");

        // Verify account persisted
        assertThat(loyaltyAccountRepository.findByUserId(userId)).isPresent();
    }

    // -- shouldRedeemPoints -------------------------------------------------------

    @Test
    void shouldRedeemPoints() {
        UUID userId = UUID.randomUUID();

        // Earn 200 points first (20000 cents = 200 rupees)
        loyaltyService.earnPoints(userId, "earn-" + UUID.randomUUID(), 20000L);

        // Redeem 50 points
        LoyaltyResponse response = loyaltyService.redeemPoints(userId, 50, "redeem-" + UUID.randomUUID());

        assertThat(response).isNotNull();
        assertThat(response.pointsBalance()).isEqualTo(150); // 200 - 50
        assertThat(response.lifetimePoints()).isEqualTo(200); // lifetime unchanged by redemption
    }

    // -- shouldRejectRedemptionWhenInsufficientBalance -----------------------------

    @Test
    void shouldRejectRedemptionWhenInsufficientBalance() {
        UUID userId = UUID.randomUUID();

        // Earn 50 points (5000 cents = 50 rupees)
        loyaltyService.earnPoints(userId, "earn-" + UUID.randomUUID(), 5000L);

        // Attempt to redeem 100 points (more than balance)
        assertThatThrownBy(() -> loyaltyService.redeemPoints(userId, 100, "redeem-" + UUID.randomUUID()))
            .isInstanceOf(ApiException.class);

        // Verify balance unchanged
        LoyaltyResponse balance = loyaltyService.getBalance(userId);
        assertThat(balance.pointsBalance()).isEqualTo(50);
    }

    // -- shouldHandleDuplicateRedemptionIdempotently ------------------------------

    @Test
    void shouldHandleDuplicateRedemptionIdempotently() {
        UUID userId = UUID.randomUUID();
        String redeemOrderId = "redeem-dup-" + UUID.randomUUID();

        // Earn 200 points (20000 cents = 200 rupees)
        loyaltyService.earnPoints(userId, "earn-" + UUID.randomUUID(), 20000L);

        // First redemption: 50 points -- succeeds
        LoyaltyResponse first = loyaltyService.redeemPoints(userId, 50, redeemOrderId);
        assertThat(first.pointsBalance()).isEqualTo(150);

        // Second redemption with same orderId -- should fail due to idempotency constraint
        assertThatThrownBy(() -> loyaltyService.redeemPoints(userId, 50, redeemOrderId))
            .isInstanceOf(RuntimeException.class);

        // Verify balance reflects only one deduction
        LoyaltyResponse balance = loyaltyService.getBalance(userId);
        assertThat(balance.pointsBalance()).isEqualTo(150);
    }
}
