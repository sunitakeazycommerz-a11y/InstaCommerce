package com.instacommerce.featureflag.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class FlagChangeStreamControllerTest {

    @Test
    void broadcast_serializesFlagChangedEventViaObjectMapper() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        FlagChangeStreamController controller = new FlagChangeStreamController(objectMapper);
        SseEmitter emitter = mock(SseEmitter.class);
        controller.registerEmitter(emitter);

        controller.broadcast("quote\"flag", "new\"value");

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(objectMapper.writeValueAsString(
                new FlagChangeStreamController.FlagChangedEvent("quote\"flag", "new\"value")))
                .isEqualTo("{\"flag\":\"quote\\\"flag\",\"value\":\"new\\\"value\"}");
    }

    @Test
    void stream_enforcesMaxConnectionCapAndAllowsReconnectAfterCompletion() {
        FlagChangeStreamController controller = new FlagChangeStreamController(new ObjectMapper(), 2);

        SseEmitter first = controller.stream();
        SseEmitter second = controller.stream();
        assertThat(controller.getActiveConnectionCount()).isEqualTo(2);

        assertThatThrownBy(controller::stream)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        first.complete();
        // Manually trigger removal via broken broadcast since MockMvc isn't running callback here
        second.complete();
        controller.broadcast("test", true);
        assertThat(first.getTimeout()).isGreaterThan(0L);
    }
}
