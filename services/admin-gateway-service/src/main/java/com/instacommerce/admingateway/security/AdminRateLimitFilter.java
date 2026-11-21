package com.instacommerce.admingateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminRateLimitFilter extends OncePerRequestFilter {

    private static final int TOO_MANY_REQUESTS = 429;
    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_TRACKED_KEYS = 4096;

    private final ObjectMapper objectMapper;
    private final int requestsPerMinute;
    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Autowired
    public AdminRateLimitFilter(
            ObjectMapper objectMapper,
            @Value("${admin-gateway.rate-limit.requests-per-minute:120}") int requestsPerMinute) {
        this.objectMapper = objectMapper;
        this.requestsPerMinute = Math.max(1, requestsPerMinute);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
            || path.startsWith("/admin/health")
            || path.startsWith("/admin/metrics")
            || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String key = resolveRateLimitKey(request);
        long now = System.currentTimeMillis();
        if (counters.size() > MAX_TRACKED_KEYS) {
            counters.entrySet().removeIf(entry -> now - entry.getValue().windowStartMillis() > WINDOW_MILLIS);
        }
        WindowCounter counter = counters.computeIfAbsent(key, ignored -> new WindowCounter(now));
        if (!counter.tryAcquire(now, requestsPerMinute)) {
            response.setStatus(TOO_MANY_REQUESTS);
            response.setHeader("Retry-After", "60");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), Map.of(
                "code", "RATE_LIMIT_EXCEEDED",
                "message", "Admin gateway rate limit exceeded",
                "timestamp", Instant.now().toString()));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String resolveRateLimitKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getName() != null) {
            return "user:" + authentication.getName();
        }
        String clientIp = AdminSecurityAuditFilter.resolveClientIp(request);
        return "ip:" + (clientIp != null ? clientIp : "unknown");
    }

    private static final class WindowCounter {
        private long windowStart;
        private int count;

        private WindowCounter(long windowStart) {
            this.windowStart = windowStart;
        }

        private synchronized long windowStartMillis() {
            return windowStart;
        }

        private synchronized boolean tryAcquire(long now, int limit) {
            if (now - windowStart >= WINDOW_MILLIS) {
                windowStart = now;
                count = 0;
            }
            if (count >= limit) {
                return false;
            }
            count++;
            return true;
        }
    }
}
