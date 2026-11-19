package com.instacommerce.mobilebff.security;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String CONTEXT_KEY = "correlationId";
    private static final Pattern SAFE_ID = Pattern.compile("^[a-zA-Z0-9._-]{1,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER_CORRELATION_ID);
        if (incoming == null || incoming.isBlank()) {
            incoming = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        }
        String correlationId = (incoming != null && SAFE_ID.matcher(incoming.trim()).matches())
                ? incoming.trim()
                : UUID.randomUUID().toString();

        exchange.getResponse().getHeaders().set(HEADER_CORRELATION_ID, correlationId);
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER_CORRELATION_ID, correlationId))
                .build();
        ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();
        return chain.filter(mutatedExchange)
                .contextWrite(ctx -> ctx.put(CONTEXT_KEY, correlationId));
    }
}
