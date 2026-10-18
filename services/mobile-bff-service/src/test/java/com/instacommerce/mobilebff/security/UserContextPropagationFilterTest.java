package com.instacommerce.mobilebff.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
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
    @DisplayName("Should pass request unchanged if no Authorization header")
    void shouldPassUnchangedWhenNoAuthHeader() {
        when(keyLoader.hasKey()).thenReturn(true);

        MockServerHttpRequest request = MockServerHttpRequest.get("/bff/mobile/v1/home").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        WebFilterChain chain = ex -> {
            assertThat(ex.getRequest().getHeaders().getFirst("X-User-Id")).isNull();
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
    }

    @Test
    @DisplayName("Should populate X-User-Id and X-User-Roles when valid token provided")
    @SuppressWarnings("unchecked")
    void shouldMutateRequestWithUserHeaders() {
        when(keyLoader.hasKey()).thenReturn(true);

        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("usr_test_999");
        Jws<Claims> jws = mock(Jws.class);
        when(jws.getPayload()).thenReturn(claims);
        when(jwtService.validateAccessToken("valid-test-token")).thenReturn(jws);
        when(jwtService.extractAuthorities(claims)).thenReturn((List) List.of(
            new SimpleGrantedAuthority("ROLE_CUSTOMER"),
            new SimpleGrantedAuthority("ROLE_VIP")
        ));

        MockServerHttpRequest request = MockServerHttpRequest.get("/bff/mobile/v1/profile")
            .header(HttpHeaders.AUTHORIZATION, "Bearer valid-test-token")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = ex -> {
            assertThat(ex.getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("usr_test_999");
            assertThat(ex.getRequest().getHeaders().getFirst("X-User-Roles")).isEqualTo("ROLE_CUSTOMER,ROLE_VIP");
            assertThat(ex.getRequest().getHeaders().getFirst("X-Customer-Tier")).isEqualTo("VIP");
            assertThat(ex.getRequest().getHeaders().getFirst("baggage")).contains("customer_tier=VIP");
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
    }
}

