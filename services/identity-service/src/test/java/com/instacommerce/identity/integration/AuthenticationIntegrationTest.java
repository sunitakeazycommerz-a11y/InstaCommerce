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
    void shouldLockAccountAfterMaxFailedAttempts() {
        authService.register(
            new RegisterRequest("lock@example.com", "Password1"),
            "127.0.0.1", "test-agent", "trace-1");

        LoginRequest badLogin = new LoginRequest("lock@example.com", "WrongPassword1", null);

        // 10 failed login attempts (MAX_FAILED_ATTEMPTS = 10)
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> authService.login(badLogin, "127.0.0.1", "test-agent", "trace"))
                .isInstanceOf(InvalidCredentialsException.class);
        }

        // 11th attempt should be rejected due to account lock
        assertThatThrownBy(() -> authService.login(badLogin, "127.0.0.1", "test-agent", "trace-11"))
            .isInstanceOf(UserInactiveException.class);

        // Verify user is locked
        User user = userRepository.findByEmailIgnoreCase("lock@example.com").orElseThrow();
        assertThat(user.getFailedAttempts()).isEqualTo(10);
        assertThat(user.getLockedUntil()).isNotNull();
        assertThat(user.getLockedUntil()).isAfter(Instant.now());
    }
}
