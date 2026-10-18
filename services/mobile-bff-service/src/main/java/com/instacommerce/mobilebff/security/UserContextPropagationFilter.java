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
            return chain.filter(exchange);
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

            String baggageHeader = "customer_tier=" + customerTier + ",order_value_bracket=" + orderValueBracket;

            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-User-Id", userId != null ? userId : "")
                .header("X-User-Roles", authorities.stream().map(GrantedAuthority::getAuthority).reduce((a, b) -> a + "," + b).orElse(""))
                .header("X-Customer-Tier", customerTier)
                .header("X-Order-Value-Bracket", orderValueBracket)
                .header("baggage", baggageHeader)
                .build();

            ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

            try (Scope scope = baggage.makeCurrent()) {
                return chain.filter(mutatedExchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
            }
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("JWT validation failed: {}", ex.getMessage());
            return chain.filter(exchange);
        }
    }
}
