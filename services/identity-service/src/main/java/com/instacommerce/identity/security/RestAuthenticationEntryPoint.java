package com.instacommerce.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.identity.dto.response.ErrorResponse;
import com.instacommerce.identity.exception.TraceIdProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final TraceIdProvider traceIdProvider;
    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(TraceIdProvider traceIdProvider, ObjectMapper objectMapper) {
        this.traceIdProvider = traceIdProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String traceId = traceIdProvider.resolveTraceId(request);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-Trace-Id", traceId);
        ErrorResponse error = new ErrorResponse(
            "AUTHENTICATION_REQUIRED",
            "Authentication is required to access this resource",
            traceId,
            Instant.now(),
            List.of());
        objectMapper.writeValue(response.getWriter(), error);
    }
}
