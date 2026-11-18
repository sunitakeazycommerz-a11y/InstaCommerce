package com.instacommerce.featureflag.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class FlagChangeStreamControllerTest {

    @Test
    void broadcast_serializesFlagChangedEventViaObjectMapper() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        FlagChangeStreamController controller = new FlagChangeStreamController(objectMapper);
        @SuppressWarnings("unchecked")
        List<SseEmitter> emitters = (List<SseEmitter>) ReflectionTestUtils.getField(controller, "emitters");
        SseEmitter emitter = mock(SseEmitter.class);
        emitters.add(emitter);

        controller.broadcast("quote\"flag", "new\"value");

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(objectMapper.writeValueAsString(
                new FlagChangeStreamController.FlagChangedEvent("quote\"flag", "new\"value")))
                .isEqualTo("{\"flag\":\"quote\\\"flag\",\"value\":\"new\\\"value\"}");
    }
}
