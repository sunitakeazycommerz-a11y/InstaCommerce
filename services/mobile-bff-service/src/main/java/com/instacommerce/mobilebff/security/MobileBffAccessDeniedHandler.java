package com.instacommerce.mobilebff.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.mobilebff.exception.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public class MobileBffAccessDeniedHandler implements ServerAccessDeniedHandler {

    private static final byte[] FALLBACK_BODY =
        "{\"type\":\"urn:instacommerce:problem:access_denied\",\"title\":\"Forbidden\",\"status\":403,\"detail\":\"Insufficient permissions\",\"code\":\"ACCESS_DENIED\"}"
            .getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper objectMapper;

    public MobileBffAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        ProblemDetail problem = GlobalExceptionHandler.createProblemDetail(
            HttpStatus.FORBIDDEN,
            "ACCESS_DENIED",
            "Insufficient permissions",
            exchange,
            null);
        byte[] payload = serializeProblemDetail(problem);
        DataBuffer buffer = response.bufferFactory().wrap(payload);
        return response.writeWith(Mono.just(buffer));
    }

    private byte[] serializeProblemDetail(ProblemDetail problem) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (problem.getType() != null) {
            body.put("type", problem.getType().toString());
        }
        if (problem.getTitle() != null) {
            body.put("title", problem.getTitle());
        }
        body.put("status", problem.getStatus());
        if (problem.getDetail() != null) {
            body.put("detail", problem.getDetail());
        }
        if (problem.getInstance() != null) {
            body.put("instance", problem.getInstance().toString());
        }
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException ex) {
            return FALLBACK_BODY;
        }
    }
}
