package com.instacommerce.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.instacommerce.identity.IdentityServiceApplication;
import com.instacommerce.identity.domain.model.User;
import com.instacommerce.identity.domain.model.UserStatus;
import com.instacommerce.identity.dto.request.LoginRequest;
import com.instacommerce.identity.dto.request.RegisterRequest;
import com.instacommerce.identity.dto.response.AuthResponse;
import com.instacommerce.identity.exception.InvalidCredentialsException;
import com.instacommerce.identity.exception.UserInactiveException;
import com.instacommerce.identity.repository.UserRepository;
import com.instacommerce.identity.service.AuthService;
import com.instacommerce.identity.service.RateLimitService;
import com.instacommerce.identity.service.TokenService;
import com.instacommerce.identity.service.UserService;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link AuthService} proving user registration, authentication,
 * invalid credential rejection, and account lockout after max failed attempts against
 * a real PostgreSQL instance with Flyway migrations.
 */
@SpringBootTest(
    classes = IdentityServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class AuthenticationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @org.junit.jupiter.api.BeforeAll
    static void runMigrations() {
        POSTGRES.start();
        org.flywaydb.core.Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserService userService;

    private final AtomicInteger refreshTokenCounter = new AtomicInteger(0);

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
            TRUNCATE TABLE
                outbox_events,
                notification_preferences,
                refresh_tokens,
                audit_log,
                users,
                shedlock
            RESTART IDENTITY CASCADE
            """);

        refreshTokenCounter.set(0);

        // TokenService mock setup: return unique refresh tokens per call
        when(tokenService.generateAccessToken(any())).thenReturn("test-access-token");
        when(tokenService.generateRefreshToken())
            .thenAnswer(inv -> "test-refresh-token-" + refreshTokenCounter.incrementAndGet());
        when(tokenService.hashRefreshToken(anyString()))
            .thenAnswer(inv -> "hash-" + inv.getArgument(0));
        when(tokenService.refreshTokenExpiresAt())
            .thenReturn(Instant.now().plusSeconds(86400));
        when(tokenService.accessTokenTtlSeconds()).thenReturn(900L);
        when(tokenService.maxRefreshTokens()).thenReturn(5);
    }

    // -- shouldRegisterUser -------------------------------------------------------

    @Test
    void shouldRegisterUser() {
        RegisterRequest request = new RegisterRequest("new@example.com", "Password1");

        AuthResponse response = authService.register(request, "127.0.0.1", "test-agent", "trace-1");

        assertThat(response).isNotNull();
        assertThat(response.accessToken()).isEqualTo("test-access-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");

        User user = userRepository.findByEmailIgnoreCase("new@example.com").orElseThrow();
        assertThat(user.getEmail()).isEqualTo("new@example.com");
        assertThat(user.getPasswordHash()).startsWith("$2"); // BCrypt hash prefix
        assertThat(user.getRoles()).contains("CUSTOMER");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getFailedAttempts()).isZero();

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE aggregate_type = 'User' AND event_type = 'UserRegistered' AND CAST(payload AS text) LIKE '%new@example.com%'",
            Integer.class);
        assertThat(outboxCount).isEqualTo(1);
    }

    // -- shouldAuthenticateWithValidCredentials -----------------------------------

    @Test
    void shouldAuthenticateWithValidCredentials() {
        // Register first
        authService.register(
            new RegisterRequest("auth@example.com", "Password1"),
            "127.0.0.1", "test-agent", "trace-1");

        // Login with correct credentials
        LoginRequest login = new LoginRequest("auth@example.com", "Password1", "test-device");
        AuthResponse response = authService.login(login, "127.0.0.1", "test-agent", "trace-2");

        assertThat(response).isNotNull();
        assertThat(response.accessToken()).isEqualTo("test-access-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");

        // Verify successful login resets failed attempts
        User user = userRepository.findByEmailIgnoreCase("auth@example.com").orElseThrow();
        assertThat(user.getFailedAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    // -- shouldRejectInvalidCredentials -------------------------------------------

    @Test
    void shouldRejectInvalidCredentials() {
        authService.register(
            new RegisterRequest("reject@example.com", "Password1"),
            "127.0.0.1", "test-agent", "trace-1");

        LoginRequest badLogin = new LoginRequest("reject@example.com", "WrongPassword1", null);

        assertThatThrownBy(() -> authService.login(badLogin, "127.0.0.1", "test-agent", "trace-2"))
            .isInstanceOf(InvalidCredentialsException.class);

        // Verify failed attempt recorded
        User user = userRepository.findByEmailIgnoreCase("reject@example.com").orElseThrow();
        assertThat(user.getFailedAttempts()).isEqualTo(1);
    }

    // -- shouldLockAccountAfterMaxFailedAttempts ----------------------------------

    @Test
    void shouldLockAccountAfterMaxFailedAttemptsAndSupportAdminUnlock() {
        authService.register(
            new RegisterRequest("lock@example.com", "Password1"),
            "127.0.0.1", "test-agent", "trace-1");

        LoginRequest badLogin = new LoginRequest("lock@example.com", "WrongPassword1", null);

        // 5 failed login attempts (MAX_FAILED_ATTEMPTS = 5)
        for (int i = 0; i < 5; i++) {
            final int attempt = i;
            assertThatThrownBy(() -> authService.login(badLogin, "127.0.0.1", "test-agent", "trace-" + attempt))
                .isInstanceOf(InvalidCredentialsException.class);
        }

        // 6th attempt should be rejected due to account lock
        assertThatThrownBy(() -> authService.login(badLogin, "127.0.0.1", "test-agent", "trace-6"))
            .isInstanceOf(UserInactiveException.class);

        // Verify user is locked
        User user = userRepository.findByEmailIgnoreCase("lock@example.com").orElseThrow();
        assertThat(user.getFailedAttempts()).isEqualTo(5);
        assertThat(user.getLockedUntil()).isNotNull();
        assertThat(user.getLockedUntil()).isAfter(Instant.now());

        Integer lockOutboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE aggregate_type = 'User' AND event_type = 'AccountLocked' AND CAST(payload AS text) LIKE '%lock@example.com%'",
            Integer.class);
        assertThat(lockOutboxCount).isEqualTo(1);

        // Customer support / admin unlock
        userService.unlockUser(user.getId(), "127.0.0.1", "admin-agent", "trace-unlock");

        // Verify user is unlocked in DB
        User unlockedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(unlockedUser.getFailedAttempts()).isZero();
        assertThat(unlockedUser.getLockedUntil()).isNull();

        // Verify AccountUnlocked event published to outbox
        Integer unlockOutboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE aggregate_type = 'User' AND event_type = 'AccountUnlocked' AND CAST(payload AS text) LIKE '%lock@example.com%'",
            Integer.class);
        assertThat(unlockOutboxCount).isEqualTo(1);

        // Subsequent valid login succeeds
        LoginRequest validLogin = new LoginRequest("lock@example.com", "Password1", "device-1");
        AuthResponse loginResponse = authService.login(validLogin, "127.0.0.1", "test-agent", "trace-valid");
        assertThat(loginResponse).isNotNull();
        assertThat(loginResponse.accessToken()).isEqualTo("test-access-token");
    }
}
