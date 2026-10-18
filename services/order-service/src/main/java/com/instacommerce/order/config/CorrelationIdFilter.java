package com.instacommerce.order.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String MDC_KEY = "correlationId";
    private static final String STORE_ID_HEADER = "X-Store-Id";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String TENANT_ID_HEADER = "X-Tenant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(CORRELATION_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, correlationId);
        MDC.put("correlation_id", correlationId);
        request.setAttribute(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_HEADER, correlationId);

        String storeId = request.getHeader(STORE_ID_HEADER);
        if (storeId == null || storeId.isBlank()) {
            storeId = request.getParameter("storeId");
            if (storeId == null || storeId.isBlank()) {
                storeId = request.getParameter("store_id");
            }
        }
        if (storeId != null && !storeId.isBlank()) {
            MDC.put("storeId", storeId);
            MDC.put("store_id", storeId);
        }

        String userId = request.getHeader(USER_ID_HEADER);
        if (userId == null || userId.isBlank()) {
            userId = request.getParameter("userId");
            if (userId == null || userId.isBlank()) {
                userId = request.getParameter("user_id");
            }
        }
        if (userId != null && !userId.isBlank()) {
            MDC.put("userId", userId);
            MDC.put("user_id", userId);
        }

        String tenantId = request.getHeader(TENANT_ID_HEADER);
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = request.getParameter("tenantId");
            if (tenantId == null || tenantId.isBlank()) {
                tenantId = request.getParameter("tenant_id");
            }
        }
        if (tenantId != null && !tenantId.isBlank()) {
            MDC.put("tenantId", tenantId);
            MDC.put("tenant_id", tenantId);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove("correlation_id");
            MDC.remove("storeId");
            MDC.remove("store_id");
            MDC.remove("userId");
            MDC.remove("user_id");
            MDC.remove("tenantId");
            MDC.remove("tenant_id");
        }
    }
}
