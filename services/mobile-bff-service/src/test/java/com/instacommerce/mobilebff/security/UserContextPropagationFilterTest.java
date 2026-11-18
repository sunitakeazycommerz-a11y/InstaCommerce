package com.instacommerce.mobilebff.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@DisplayName("UserContextPropagationFilter Unit Tests")
class UserContextPropagationFilterTest {

    private JwtService jwtService;
    private JwtKeyLoader keyLoader;
    private UserContextPropagationFilter filter;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        keyLoader = mock(JwtKeyLoader.class);
        filter = new UserContextPropagationFilter(jwtService, keyLoader);
    }

    @Test
    @DisplayName("Should strip spoofed user and internal headers when no Authorization header")
    void shouldPassUnchangedWhenNoAuthHeader() {
        when(keyLoader.hasKey()).thenReturn(true);

        MockServerHttpRequest request = MockServerHttpRequest.get("/bff/mobile/v1/home")
            .header("X-User-Id", "spoofed-user")
            .header("X-User-Roles", "ROLE_ADMIN")
            .header("X-Internal-Service", "spoofed-svc")
            .header("X-Internal-Token", "spoofed-secret")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        WebFilterChain chain = ex -> {
            assertThat(ex.getRequest().getHeaders().getFirst("X-User-Id")).isNull();
            assertThat(ex.getRequest().getHeaders().getFirst("X-User-Roles")).isNull();
            assertThat(ex.getRequest().getHeaders().getFirst("X-Internal-Service")).isNull();
            assertThat(ex.getRequest().getHeaders().getFirst("X-Internal-Token")).isNull();
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
    }

    @Test
    @DisplayName("Should strip spoofed user and internal headers when JWT validation fails")
    void shouldStripSpoofedHeadersWhenJwtInvalid() {
        when(keyLoader.hasKey()).thenReturn(true);
        when(jwtService.validateAccessToken("bad-token")).thenThrow(new JwtException("invalid"));

        MockServerHttpRequest request = MockServerHttpRequest.get("/bff/mobile/v1/profile")
            .header(HttpHeaders.AUTHORIZATION, "Bearer bad-token")
            .header("X-User-Id", "spoofed-user")
            .header("X-User-Roles", "ROLE_ADMIN")
            .header("X-Internal-Service", "spoofed-svc")
            .header("X-Internal-Token", "spoofed-secret")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        WebFilterChain chain = ex -> {
            assertThat(ex.getRequest().getHeaders().getFirst("X-User-Id")).isNull();
            assertThat(ex.getRequest().getHeaders().getFirst("X-User-Roles")).isNull();
            assertThat(ex.getRequest().getHeaders().getFirst("X-Internal-Service")).isNull();
            assertThat(ex.getRequest().getHeaders().getFirst("X-Internal-Token")).isNull();
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
    }

    @Test
    @DisplayName("Should populate X-User-Id and X-User-Roles and strip internal headers when valid token provided")
    @SuppressWarnings("unchecked")
    void shouldMutateRequestWithUserHeaders() {
        when(keyLoader.hasKey()).thenReturn(true);

        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("usr_test_999");
        Jws<Claims> jws = mock(Jws.class);
        when(jws.getPayload()).thenReturn(claims);
        when(jwtService.validateAccessToken("valid-test-token")).thenReturn(jws);
        Collection<? extends GrantedAuthority> authorities = List.of(
            new SimpleGrantedAuthority("ROLE_CUSTOMER"),
            new SimpleGrantedAuthority("ROLE_VIP")
        );
        when(jwtService.extractAuthorities(claims)).thenAnswer(inv -> authorities);

        MockServerHttpRequest request = MockServerHttpRequest.get("/bff/mobile/v1/profile")
            .header(HttpHeaders.AUTHORIZATION, "Bearer valid-test-token")
            .header("X-Internal-Service", "spoofed-svc")
            .header("X-Internal-Token", "spoofed-secret")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = ex -> {
            assertThat(ex.getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("usr_test_999");
            assertThat(ex.getRequest().getHeaders().getFirst("X-User-Roles")).isEqualTo("ROLE_CUSTOMER,ROLE_VIP");
            assertThat(ex.getRequest().getHeaders().getFirst("X-Customer-Tier")).isEqualTo("VIP");
            assertThat(ex.getRequest().getHeaders().getFirst("baggage")).contains("customer_tier=VIP");
            assertThat(ex.getRequest().getHeaders().getFirst("X-Internal-Service")).isNull();
            assertThat(ex.getRequest().getHeaders().getFirst("X-Internal-Token")).isNull();
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
    }
}
