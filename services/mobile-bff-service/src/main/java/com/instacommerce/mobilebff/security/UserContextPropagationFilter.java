package com.instacommerce.mobilebff.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Scope;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class UserContextPropagationFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(UserContextPropagationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USER_ROLES = "X-User-Roles";
    private static final String HEADER_INTERNAL_SERVICE = "X-Internal-Service";
    private static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    private final JwtService jwtService;
    private final JwtKeyLoader keyLoader;

    public UserContextPropagationFilter(JwtService jwtService, JwtKeyLoader keyLoader) {
        this.jwtService = jwtService;
        this.keyLoader = keyLoader;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX) || !keyLoader.hasKey()) {
            return chain.filter(stripUntrustedHeaders(exchange));
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        try {
            Jws<Claims> claimsJws = jwtService.validateAccessToken(token);
            Claims claims = claimsJws.getPayload();
            String userId = claims.getSubject();
            Collection<? extends GrantedAuthority> authorities = jwtService.extractAuthorities(claims);

            Authentication authentication = new UsernamePasswordAuthenticationToken(userId, token, authorities);

            String customerTier = claims.get("tier", String.class);
            if (customerTier == null) {
                customerTier = claims.get("customer_tier", String.class);
            }
            if (customerTier == null && authorities.stream().anyMatch(a -> a.getAuthority().contains("VIP"))) {
                customerTier = "VIP";
            }
            if (customerTier == null) {
                customerTier = exchange.getRequest().getHeaders().getFirst("X-Customer-Tier");
            }
            if (customerTier == null || customerTier.isBlank()) {
                customerTier = "STANDARD";
            }

            String orderValueBracket = claims.get("order_value_bracket", String.class);
            if (orderValueBracket == null) {
                orderValueBracket = exchange.getRequest().getHeaders().getFirst("X-Order-Value-Bracket");
            }
            if (orderValueBracket == null || orderValueBracket.isBlank()) {
                orderValueBracket = "STANDARD";
            }

            Baggage baggage = Baggage.current().toBuilder()
                .put("customer_tier", customerTier)
                .put("order_value_bracket", orderValueBracket)
                .build();

            final String resolvedCustomerTier = customerTier;
            final String resolvedOrderValueBracket = orderValueBracket;
            String baggageHeader = "customer_tier=" + resolvedCustomerTier + ",order_value_bracket=" + resolvedOrderValueBracket;
            String roles = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .reduce((a, b) -> a + "," + b)
                .orElse("");

            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(HEADER_INTERNAL_SERVICE);
                    headers.remove(HEADER_INTERNAL_TOKEN);
                    headers.set(HEADER_USER_ID, userId != null ? userId : "");
                    headers.set(HEADER_USER_ROLES, roles);
                    headers.set("X-Customer-Tier", resolvedCustomerTier);
                    headers.set("X-Order-Value-Bracket", resolvedOrderValueBracket);
                    headers.set("baggage", baggageHeader);
                })
                .build();

            ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

            try (Scope scope = baggage.makeCurrent()) {
                return chain.filter(mutatedExchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
            }
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("JWT validation failed: {}", ex.getMessage());
            return chain.filter(stripUntrustedHeaders(exchange));
        }
    }

    private ServerWebExchange stripUntrustedHeaders(ServerWebExchange exchange) {
        ServerHttpRequest sanitizedRequest = exchange.getRequest().mutate()
            .headers(headers -> {
                headers.remove(HEADER_USER_ID);
                headers.remove(HEADER_USER_ROLES);
                headers.remove(HEADER_INTERNAL_SERVICE);
                headers.remove(HEADER_INTERNAL_TOKEN);
            })
            .build();
        return exchange.mutate().request(sanitizedRequest).build();
    }
}
