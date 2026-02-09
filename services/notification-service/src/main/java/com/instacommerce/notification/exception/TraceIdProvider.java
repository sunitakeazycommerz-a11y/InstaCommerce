package com.instacommerce.notification.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class TraceIdProvider {
    public String resolveTraceId(HttpServletRequest request) {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = firstHeader(request, "X-B3-TraceId", "X-Trace-Id", "traceparent", "X-Request-Id");
        }
        if (traceId != null && traceId.startsWith("00-")) {
            String[] parts = traceId.split("-");
            if (parts.length > 1) {
                traceId = parts[1];
            }
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        return traceId;
    }

    private String firstHeader(HttpServletRequest request, String... names) {
        if (request == null) {
            return null;
        }
        for (String name : names) {
            String value = request.getHeader(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
