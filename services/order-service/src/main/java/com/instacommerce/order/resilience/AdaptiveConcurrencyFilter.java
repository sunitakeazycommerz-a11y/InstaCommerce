package com.instacommerce.order.resilience;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filter applying TCP Vegas gradient-based adaptive concurrency limits to incoming API requests.
 * Health check endpoints (/actuator/**) are explicitly exempted to prevent probe timeouts.
 * Excess requests are dropped with HTTP 429 and Retry-After: 1 header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class AdaptiveConcurrencyFilter extends OncePerRequestFilter {

    private final VegasConcurrencyLimiter limiter;

    public AdaptiveConcurrencyFilter(VegasConcurrencyLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || path.startsWith("/actuator") || path.startsWith("/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!limiter.tryAcquire()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "1");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Too Many Requests\",\"message\":\"Service adaptive concurrency limit exceeded\"}");
            return;
        }

        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            limiter.onSample(duration);
        }
    }
}
