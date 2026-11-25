package com.instacommerce.mobilebff.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
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
    private static final String HEADER_CUSTOMER_TIER = "X-Customer-Tier";
    private static final String HEADER_ORDER_VALUE_BRACKET = "X-Order-Value-Bracket";
    private static final String HEADER_BAGGAGE = "baggage";
    private static final String DEFAULT_VALUE = "STANDARD";

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
            if (customerTier == null || customerTier.isBlank()) {
                customerTier = claims.get("customer_tier", String.class);
            }
            if ((customerTier == null || customerTier.isBlank())
                    && authorities.stream().anyMatch(a -> a.getAuthority().contains("VIP"))) {
                customerTier = "VIP";
            }
            final String resolvedCustomerTier = (customerTier == null || customerTier.isBlank())
                    ? DEFAULT_VALUE
                    : customerTier.trim();

            String orderValueBracket = claims.get("order_value_bracket", String.class);
            final String resolvedOrderValueBracket = (orderValueBracket == null || orderValueBracket.isBlank())
                    ? DEFAULT_VALUE
                    : orderValueBracket.trim();

            String baggageHeader = "customer_tier=" + resolvedCustomerTier
                    + ",order_value_bracket=" + resolvedOrderValueBracket;
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
                    headers.set(HEADER_CUSTOMER_TIER, resolvedCustomerTier);
                    headers.set(HEADER_ORDER_VALUE_BRACKET, resolvedOrderValueBracket);
                    headers.set(HEADER_BAGGAGE, baggageHeader);
                })
                .build();

            ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();
            return chain.filter(mutatedExchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
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
                headers.remove(HEADER_CUSTOMER_TIER);
                headers.remove(HEADER_ORDER_VALUE_BRACKET);
                headers.remove(HEADER_BAGGAGE);
            })
            .build();
        return exchange.mutate().request(sanitizedRequest).build();
    }
}
