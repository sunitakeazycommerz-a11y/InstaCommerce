package com.instacommerce.featureflag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/flags")
public class FlagChangeStreamController {

    private static final Logger log = LoggerFactory.getLogger(FlagChangeStreamController.class);
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper;

    public FlagChangeStreamController() {
        this(new ObjectMapper());
    }

    @Autowired
    public FlagChangeStreamController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        log.info("flags.sse_client_connected active_connections={}", emitters.size());
        return emitter;
    }

    void registerEmitter(SseEmitter emitter) {
        emitters.add(emitter);
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
                emitters.remove(emitter);
            }
        }
    }

    public record FlagChangedEvent(String flag, Object value) {}
}
