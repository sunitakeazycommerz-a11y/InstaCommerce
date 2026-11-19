package com.instacommerce.admingateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_CORRELATION_ID = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE_ID = Pattern.compile("^[a-zA-Z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER_CORRELATION_ID);
        if (incoming == null || incoming.isBlank()) {
            incoming = request.getHeader("X-Request-Id");
        }
        String correlationId = (incoming != null && SAFE_ID.matcher(incoming.trim()).matches())
                ? incoming.trim()
                : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, correlationId);
        MDC.put(HEADER_CORRELATION_ID, correlationId);
        response.setHeader(HEADER_CORRELATION_ID, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove(HEADER_CORRELATION_ID);
        }
    }
}
