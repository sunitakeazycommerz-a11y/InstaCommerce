package com.instacommerce.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.instacommerce.identity.config.IdentityProperties;
import com.instacommerce.identity.domain.model.RefreshToken;
import com.instacommerce.identity.domain.model.User;
import com.instacommerce.identity.domain.model.UserStatus;
import com.instacommerce.identity.dto.mapper.UserMapper;
import com.instacommerce.identity.dto.request.RefreshRequest;
import com.instacommerce.identity.exception.ApiException;
import com.instacommerce.identity.exception.TokenRevokedException;
import com.instacommerce.identity.exception.TraceIdProvider;
import com.instacommerce.identity.infrastructure.metrics.AuthMetrics;
import com.instacommerce.identity.repository.RefreshTokenRepository;
import com.instacommerce.identity.repository.UserRepository;
import com.instacommerce.identity.security.DefaultJwtService;
import com.instacommerce.identity.security.JwtAuthenticationFilter;
import com.instacommerce.identity.security.JwtKeyLoader;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

class TokenRevocationAndAdminSecurityTest {

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock();
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    @Test
    @DisplayName("Logout and token reuse revoke active access tokens immediately while fresh re-login remains accepted")
    void revokedAccessTokensRejectedImmediatelyAcrossLogoutAndReuse() throws Exception {
        IdentityProperties props = new IdentityProperties();
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair pair = kpg.generateKeyPair();
        RSAPrivateKey priv = (RSAPrivateKey) pair.getPrivate();
        RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
        props.getJwt().setIssuer("instacommerce-identity");
        props.getJwt().setPrivateKey(Base64.getEncoder().encodeToString(priv.getEncoded()));
        props.getJwt().setPublicKey(Base64.getEncoder().encodeToString(pub.getEncoded()));
        props.getToken().setAccessTtlSeconds(900);

        JwtKeyLoader keyLoader = new JwtKeyLoader(props);
        DefaultJwtService jwtService = new DefaultJwtService(keyLoader, props);
        TokenBlocklistService blocklistService = new TokenBlocklistService(null, props, null);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtService, new TraceIdProvider(), objectMapper, blocklistService);

        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setEmail("alice@instacommerce.dev");
        user.setRoles(List.of("CUSTOMER"));
        user.setStatus(UserStatus.ACTIVE);

        String preLogoutToken = jwtService.generateAccessToken(user);

        MockHttpServletRequest reqBeforeLogout = new MockHttpServletRequest("GET", "/users/me");
        reqBeforeLogout.addHeader("Authorization", "Bearer " + preLogoutToken);
        MockHttpServletResponse resBeforeLogout = new MockHttpServletResponse();
        AtomicBoolean passedBefore = new AtomicBoolean(false);
        FilterChain chainBefore = (r, s) -> passedBefore.set(true);
        filter.doFilter(reqBeforeLogout, resBeforeLogout, chainBefore);
        assertThat(passedBefore.get()).isTrue();
        assertThat(resBeforeLogout.getStatus()).isEqualTo(200);

        Thread.sleep(5);
        blocklistService.revokeAllUserTokens(userId);
        Thread.sleep(5);
        String postLoginToken = jwtService.generateAccessToken(user);

        MockHttpServletRequest reqAfterLogout = new MockHttpServletRequest("GET", "/users/me");
        reqAfterLogout.addHeader("Authorization", "Bearer " + preLogoutToken);
        MockHttpServletResponse resAfterLogout = new MockHttpServletResponse();
        AtomicBoolean passedAfter = new AtomicBoolean(false);
        filter.doFilter(reqAfterLogout, resAfterLogout, (r, s) -> passedAfter.set(true));

        assertThat(passedAfter.get()).isFalse();
        assertThat(resAfterLogout.getStatus()).isEqualTo(401);
        assertThat(resAfterLogout.getContentAsString()).contains("TOKEN_REVOKED");

        MockHttpServletRequest reqFreshLogin = new MockHttpServletRequest("GET", "/users/me");
        reqFreshLogin.addHeader("Authorization", "Bearer " + postLoginToken);
        MockHttpServletResponse resFreshLogin = new MockHttpServletResponse();
        AtomicBoolean passedFresh = new AtomicBoolean(false);
        filter.doFilter(reqFreshLogin, resFreshLogin, (r, s) -> passedFresh.set(true));

        assertThat(passedFresh.get()).isTrue();
        assertThat(resFreshLogin.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Refresh token reuse revokes refresh tokens, blocks active access tokens, and publishes TokenReuseDetected outbox event")
    void tokenReuseRevokesSessionAndPublishesOutbox() {
        UserRepository userRepo = mock(UserRepository.class);
        RefreshTokenRepository refreshRepo = mock(RefreshTokenRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        TokenService tokenService = mock(TokenService.class);
        AuditService auditService = mock(AuditService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        UserService userService = mock(UserService.class);
        OutboxService outboxService = mock(OutboxService.class);
        TokenBlocklistService blocklistService = mock(TokenBlocklistService.class);

        AuthService authService = new AuthService(
                userRepo, refreshRepo, encoder, tokenService, auditService,
                rateLimit, new AuthMetrics(new SimpleMeterRegistry()), userService, outboxService,
                null, providerOf(blocklistService));

        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setStatus(UserStatus.ACTIVE);

        RefreshToken reused = new RefreshToken();
        reused.setId(UUID.randomUUID());
        reused.setUser(user);
        reused.setRevoked(true);
        reused.setExpiresAt(Instant.now().plusSeconds(3600));

        when(tokenService.hashRefreshToken("reused-rt")).thenReturn("hash");
        when(refreshRepo.findByTokenHash("hash")).thenReturn(Optional.of(reused));
        when(refreshRepo.revokeAllByUserId(userId)).thenReturn(3);

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("reused-rt"), "10.0.0.1", "ua", "trace-1"))
                .isInstanceOf(TokenRevokedException.class);

        verify(refreshRepo).revokeAllByUserId(userId);
        verify(blocklistService).revokeAllUserTokens(userId);
        verify(outboxService).publish(eq("User"), eq(userId.toString()), eq("TokenReuseDetected"), any(), eq("trace-1"));
    }

    @Test
    @DisplayName("Admin updateUserRoles validates roles against Role enum, updates roles, and revokes active sessions")
    void adminRoleUpdateValidatesAndRevokesSessions() {
        UserRepository userRepo = mock(UserRepository.class);
        UserMapper mapper = Mappers.getMapper(UserMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuditService audit = mock(AuditService.class);
        RefreshTokenRepository refreshRepo = mock(RefreshTokenRepository.class);
        OutboxService outbox = mock(OutboxService.class);
        TokenBlocklistService blocklist = mock(TokenBlocklistService.class);

        ObjectProvider<TokenBlocklistService> blocklistProvider = providerOf(blocklist);

        UserService service = new UserService(
                userRepo, mapper, encoder, audit, refreshRepo, outbox, null, blocklistProvider);

        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setEmail("bob@instacommerce.dev");
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(List.of("CUSTOMER"));

        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.updateUserRoles(userId, List.of("SUPER_HACKER"), "10.0.0.1", "ua", "t1"))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("INVALID_ROLE");

        var updated = service.updateUserRoles(userId, List.of("admin", "support", "ADMIN"), "10.0.0.1", "ua", "t1");
        assertThat(updated.roles()).containsExactly("ADMIN", "SUPPORT");
        verify(refreshRepo).revokeAllByUserId(userId);
        verify(blocklist).revokeAllUserTokens(userId);
    }

    @Test
    @DisplayName("TokenBlocklistService fails closed on lookup error, propagates write error, and records failure counters")
    void tokenBlocklistFailsClosedAndIncrementsMetricsOnRedisFailure() {
        StringRedisTemplate redisTemplate = mock();
        ValueOperations<String, String> valueOps = mock();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.hasKey(any())).thenThrow(new RedisConnectionFailureException("Redis down"));
        when(valueOps.get(any())).thenThrow(new RedisConnectionFailureException("Redis down"));
        doThrow(new RedisConnectionFailureException("Redis down"))
                .when(valueOps).set(any(), any(), any(Duration.class));

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> registryProvider = providerOf(meterRegistry);
        IdentityProperties props = new IdentityProperties();
        props.getToken().setAccessTtlSeconds(900);

        TokenBlocklistService service = new TokenBlocklistService(
                providerOf(redisTemplate), props, registryProvider);

        assertThat(service.isTokenRevoked("jti-1", "user-1", new Date(), System.currentTimeMillis())).isTrue();
        assertThat(service.isTokenRevoked(null, "user-1", new Date(), System.currentTimeMillis())).isTrue();
        assertThat(meterRegistry.get("auth.token.blocklist.redis.failures").tag("operation", "lookup").counter().count())
                .isEqualTo(2.0);

        assertThatThrownBy(() -> service.revokeAccessToken("jti-2", 60))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.revokeAllUserTokens(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(meterRegistry.get("auth.token.blocklist.redis.failures").tag("operation", "write").counter().count())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("UserService.unlockUser clears Redis failed login keys via cursor SCAN instead of blocking KEYS")
    void unlockUserUsesRedisScanInsteadOfKeys() {
        UserRepository userRepo = mock(UserRepository.class);
        UserMapper mapper = Mappers.getMapper(UserMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuditService audit = mock(AuditService.class);
        RefreshTokenRepository refreshRepo = mock(RefreshTokenRepository.class);
        OutboxService outbox = mock(OutboxService.class);
        StringRedisTemplate redisTemplate = mock();
        Cursor<String> cursor = mock();

        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setEmail("locked@instacommerce.dev");
        user.setFailedAttempts(5);
        user.setLockedUntil(Instant.now().plusSeconds(600));

        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(
                "identity:failed_logins:locked@instacommerce.dev:10.0.0.1",
                "identity:failed_logins:locked@instacommerce.dev:10.0.0.2");
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

        UserService service = new UserService(
                userRepo, mapper, encoder, audit, refreshRepo, outbox, providerOf(redisTemplate), null);

        service.unlockUser(userId, "10.0.0.9", "admin-ua", "trace-unlock");

        verify(redisTemplate, never()).keys(any());
        verify(redisTemplate).scan(any(ScanOptions.class));
        verify(redisTemplate).delete(anyCollection());
        verify(cursor).close();
    }
}
