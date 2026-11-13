package com.instacommerce.mobilebff.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public class MobileBffAccessDeniedHandler implements ServerAccessDeniedHandler {

    private static final byte[] FALLBACK_BODY =
        "{\"code\":\"ACCESS_DENIED\",\"message\":\"Insufficient permissions\"}".getBytes(StandardCharsets.UTF_8);

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
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] payload = serializeForbiddenBody();
        DataBuffer buffer = response.bufferFactory().wrap(payload);
        return response.writeWith(Mono.just(buffer));
    }

    private byte[] serializeForbiddenBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "ACCESS_DENIED");
        body.put("message", "Insufficient permissions");
        body.put("timestamp", Instant.now().toString());
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException ex) {
            return FALLBACK_BODY;
        }
    }
}
