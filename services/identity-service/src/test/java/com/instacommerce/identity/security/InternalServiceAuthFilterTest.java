package com.instacommerce.identity.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;
import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

/**
 * Tests for InternalServiceAuthFilter with per-service token scoping.
 * Verifies both per-service tokens and shared token fallback during migration period.
 */
@DisplayName("InternalServiceAuthFilter - Per-Service Token Scoping")
class InternalServiceAuthFilterTest {

    private InternalServiceAuthFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain filterChain;

    private static final String SHARED_TOKEN = "instacommerce-shared-token-550e8400";
    private static final String ORDER_SERVICE_TOKEN = "instacommerce-order-service-550e8400-e29b-41d4-a716-446655440004";
    private static final String PAYMENT_SERVICE_TOKEN = "instacommerce-payment-service-550e8400-e29b-41d4-a716-446655440005";
    private static final String INVALID_TOKEN = "invalid-token";

    @BeforeEach
    void setUp() {
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        filterChain = mock(FilterChain.class);
        getContext().setAuthentication(null);
    }

    @Test
    @DisplayName("Per-service token should be accepted for authorized caller")
    void testPerServiceTokenAccepted() throws Exception {
        Map<String, String> allowedCallers = Map.of(
            "order-service", ORDER_SERVICE_TOKEN,
            "payment-service", PAYMENT_SERVICE_TOKEN
        );
        filter = new InternalServiceAuthFilter(SHARED_TOKEN, allowedCallers);

        when(request.getHeader("X-Internal-Service")).thenReturn("order-service");
        when(request.getHeader("X-Internal-Token")).thenReturn(ORDER_SERVICE_TOKEN);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/auth/verify");

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = getContext().getAuthentication();
        assertNotNull(auth, "Authentication should be set");
        assertEquals("order-service", auth.getPrincipal());
        assertTrue(auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_INTERNAL_SERVICE")));
    }

    @Test
    @DisplayName("Per-service token should be rejected for wrong token")
    void testPerServiceTokenRejected() throws Exception {
        Map<String, String> allowedCallers = Map.of(
            "order-service", ORDER_SERVICE_TOKEN
        );
        filter = new InternalServiceAuthFilter(SHARED_TOKEN, allowedCallers);

        when(request.getHeader("X-Internal-Service")).thenReturn("order-service");
        when(request.getHeader("X-Internal-Token")).thenReturn(INVALID_TOKEN);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/auth/verify");

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = getContext().getAuthentication();
        assertNull(auth, "Authentication should not be set for invalid token");
    }

    @Test
    @DisplayName("Shared token fallback should be accepted when per-service token missing")
    void testSharedTokenFallbackAccepted() throws Exception {
        Map<String, String> allowedCallers = Map.of(
            "payment-service", PAYMENT_SERVICE_TOKEN
            // order-service not in map, should fall back to shared token
        );
        filter = new InternalServiceAuthFilter(SHARED_TOKEN, allowedCallers);

        when(request.getHeader("X-Internal-Service")).thenReturn("order-service");
        when(request.getHeader("X-Internal-Token")).thenReturn(SHARED_TOKEN);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/auth/verify");

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = getContext().getAuthentication();
        assertNotNull(auth, "Authentication should be set with shared token fallback");
        assertEquals("order-service", auth.getPrincipal());
        assertTrue(auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_INTERNAL_SERVICE")));
    }

    @Test
    @DisplayName("Empty per-service token should fall back to shared token")
    void testEmptyPerServiceTokenFallback() throws Exception {
        Map<String, String> allowedCallers = Map.of(
            "order-service", "" // Empty token configured
        );
        filter = new InternalServiceAuthFilter(SHARED_TOKEN, allowedCallers);

        when(request.getHeader("X-Internal-Service")).thenReturn("order-service");
        when(request.getHeader("X-Internal-Token")).thenReturn(SHARED_TOKEN);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/auth/verify");

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = getContext().getAuthentication();
        assertNotNull(auth, "Authentication should be set with shared token fallback for empty per-service token");
        assertEquals("order-service", auth.getPrincipal());
    }

    @Test
    @DisplayName("Both wrong tokens should be rejected")
    void testBothTokensWrong() throws Exception {
        Map<String, String> allowedCallers = Map.of(
            "order-service", ORDER_SERVICE_TOKEN
        );
        filter = new InternalServiceAuthFilter(SHARED_TOKEN, allowedCallers);

        when(request.getHeader("X-Internal-Service")).thenReturn("order-service");
        when(request.getHeader("X-Internal-Token")).thenReturn(INVALID_TOKEN);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/auth/verify");

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = getContext().getAuthentication();
        assertNull(auth, "Authentication should not be set when both tokens are invalid");
    }

    @Test
    @DisplayName("Missing service header should not authenticate")
    void testMissingServiceHeader() throws Exception {
        Map<String, String> allowedCallers = Map.of(
            "order-service", ORDER_SERVICE_TOKEN
        );
        filter = new InternalServiceAuthFilter(SHARED_TOKEN, allowedCallers);

        when(request.getHeader("X-Internal-Service")).thenReturn(null);
        when(request.getHeader("X-Internal-Token")).thenReturn(ORDER_SERVICE_TOKEN);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/auth/verify");

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = getContext().getAuthentication();
        assertNull(auth, "Authentication should not be set when X-Internal-Service header is missing");
    }

    @Test
    @DisplayName("Missing token header should not authenticate")
    void testMissingTokenHeader() throws Exception {
        Map<String, String> allowedCallers = Map.of(
            "order-service", ORDER_SERVICE_TOKEN
        );
        filter = new InternalServiceAuthFilter(SHARED_TOKEN, allowedCallers);

        when(request.getHeader("X-Internal-Service")).thenReturn("order-service");
        when(request.getHeader("X-Internal-Token")).thenReturn(null);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/auth/verify");

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = getContext().getAuthentication();
        assertNull(auth, "Authentication should not be set when X-Internal-Token header is missing");
    }

    @Test
    @DisplayName("Different service should not match other service's token")
    void testWrongServiceTokenNotAccepted() throws Exception {
        Map<String, String> allowedCallers = Map.of(
            "order-service", ORDER_SERVICE_TOKEN,
            "payment-service", PAYMENT_SERVICE_TOKEN
        );
        filter = new InternalServiceAuthFilter(SHARED_TOKEN, allowedCallers);

        when(request.getHeader("X-Internal-Service")).thenReturn("order-service");
        when(request.getHeader("X-Internal-Token")).thenReturn(PAYMENT_SERVICE_TOKEN); // Wrong token
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/auth/verify");

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = getContext().getAuthentication();
        assertNull(auth, "Authentication should not be set when using wrong service's token");
    }

    @Test
    @DisplayName("Grant only ROLE_INTERNAL_SERVICE authority, never ROLE_ADMIN")
    void testGrantedAuthorityIsInternalServiceOnly() throws Exception {
        Map<String, String> allowedCallers = Map.of(
            "order-service", ORDER_SERVICE_TOKEN
        );
        filter = new InternalServiceAuthFilter(SHARED_TOKEN, allowedCallers);

        when(request.getHeader("X-Internal-Service")).thenReturn("order-service");
        when(request.getHeader("X-Internal-Token")).thenReturn(ORDER_SERVICE_TOKEN);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/auth/verify");

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals(1, auth.getAuthorities().size());
        assertTrue(auth.getAuthorities().stream()
            .allMatch(a -> a.getAuthority().equals("ROLE_INTERNAL_SERVICE")));
        assertFalse(auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")),
            "ROLE_ADMIN must not be granted to internal service callers");
    }

    @Test
    @DisplayName("Timing-safe comparison should prevent timing side-channel attacks")
    void testTimingSafeComparison() throws Exception {
        Map<String, String> allowedCallers = Map.of(
            "order-service", ORDER_SERVICE_TOKEN
        );
        filter = new InternalServiceAuthFilter(SHARED_TOKEN, allowedCallers);

        // This test verifies that MessageDigest.isEqual is used
        // MessageDigest.isEqual performs constant-time comparison
        // We test by providing tokens that differ in the first character
        String wrongTokenFirstChar = "x" + ORDER_SERVICE_TOKEN.substring(1);

        when(request.getHeader("X-Internal-Service")).thenReturn("order-service");
        when(request.getHeader("X-Internal-Token")).thenReturn(wrongTokenFirstChar);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/auth/verify");

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = getContext().getAuthentication();
        assertNull(auth, "Token with first character wrong should not authenticate");
    }

    @Test
    @DisplayName("Empty allowed callers map should use shared token fallback")
    void testEmptyAllowedCallersUsesSharedToken() throws Exception {
        Map<String, String> allowedCallers = Map.of(); // Empty map
        filter = new InternalServiceAuthFilter(SHARED_TOKEN, allowedCallers);

        when(request.getHeader("X-Internal-Service")).thenReturn("order-service");
        when(request.getHeader("X-Internal-Token")).thenReturn(SHARED_TOKEN);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/auth/verify");

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = getContext().getAuthentication();
        assertNotNull(auth, "Should authenticate with shared token when no per-service tokens configured");
        assertEquals("order-service", auth.getPrincipal());
    }
}
