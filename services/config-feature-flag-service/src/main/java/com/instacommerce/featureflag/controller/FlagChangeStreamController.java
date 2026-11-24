package com.instacommerce.featureflag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/flags")
public class FlagChangeStreamController {

    private static final Logger log = LoggerFactory.getLogger(FlagChangeStreamController.class);
    private static final long SSE_TIMEOUT_MS = 1_800_000L;
    private static final int DEFAULT_MAX_CONNECTIONS = 200;

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final ObjectMapper objectMapper;
    private final int maxConnections;

    public FlagChangeStreamController() {
        this(new ObjectMapper(), DEFAULT_MAX_CONNECTIONS);
    }

    public FlagChangeStreamController(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_MAX_CONNECTIONS);
    }

    @Autowired
    public FlagChangeStreamController(
            ObjectMapper objectMapper,
            @Value("${feature-flag.stream.max-connections:" + DEFAULT_MAX_CONNECTIONS + "}") int maxConnections) {
        this.objectMapper = objectMapper;
        this.maxConnections = Math.max(1, maxConnections);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        while (true) {
            int current = activeConnections.get();
            if (current >= maxConnections) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Maximum concurrent flag stream connections reached");
            }
            if (activeConnections.compareAndSet(current, current + 1)) {
                break;
            }
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> removeEmitter(emitter));
        emitter.onTimeout(() -> removeEmitter(emitter));
        emitter.onError(e -> removeEmitter(emitter));
        log.info("flags.sse_client_connected active_connections={}", activeConnections.get());
        return emitter;
    }

    void registerEmitter(SseEmitter emitter) {
        emitters.add(emitter);
        activeConnections.incrementAndGet();
    }

    int getActiveConnectionCount() {
        return activeConnections.get();
    }

    private void removeEmitter(SseEmitter emitter) {
        if (emitters.remove(emitter)) {
            activeConnections.updateAndGet(c -> Math.max(0, c - 1));
        }
    }

    public void broadcast(String flagKey, Object newValue) {
        String data;
        try {
            data = objectMapper.writeValueAsString(new FlagChangedEvent(flagKey, newValue));
        } catch (Exception e) {
            log.warn("Failed to serialize FlagChangedEvent for flag={}: {}", flagKey, e.getMessage());
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("flag-change")
                        .data(data, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                removeEmitter(emitter);
            }
        }
    }

    public record FlagChangedEvent(String flag, Object value) {}
}
