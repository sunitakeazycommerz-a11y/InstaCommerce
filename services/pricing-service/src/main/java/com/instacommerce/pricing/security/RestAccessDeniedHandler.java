package com.instacommerce.pricing.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.pricing.dto.response.ErrorResponse;
import com.instacommerce.pricing.exception.TraceIdProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {
    private final TraceIdProvider traceIdProvider;
    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(TraceIdProvider traceIdProvider, ObjectMapper objectMapper) {
        this.traceIdProvider = traceIdProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        String traceId = traceIdProvider.resolveTraceId(request);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-Trace-Id", traceId);
        ErrorResponse error = new ErrorResponse(
            "ACCESS_DENIED",
            "Insufficient permissions",
            traceId,
            Instant.now(),
            List.of());
        objectMapper.writeValue(response.getWriter(), error);
    }
}
