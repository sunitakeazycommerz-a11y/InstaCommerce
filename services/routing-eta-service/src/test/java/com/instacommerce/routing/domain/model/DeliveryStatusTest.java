package com.instacommerce.routing.domain.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryStatusTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldMapLegacyAndCanonicalStatuses() {
        assertThat(DeliveryStatus.fromString("ASSIGNED")).isEqualTo(DeliveryStatus.RIDER_ASSIGNED);
        assertThat(DeliveryStatus.fromString("IN_TRANSIT")).isEqualTo(DeliveryStatus.EN_ROUTE);
        assertThat(DeliveryStatus.fromString("RIDER_ASSIGNED")).isEqualTo(DeliveryStatus.RIDER_ASSIGNED);
        assertThat(DeliveryStatus.fromString("EN_ROUTE")).isEqualTo(DeliveryStatus.EN_ROUTE);
        assertThat(DeliveryStatus.fromString("NEAR_DESTINATION")).isEqualTo(DeliveryStatus.NEAR_DESTINATION);
        assertThat(DeliveryStatus.fromString("PICKED_UP")).isEqualTo(DeliveryStatus.PICKED_UP);
        assertThat(DeliveryStatus.fromString("DELIVERED")).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(DeliveryStatus.fromString("FAILED")).isEqualTo(DeliveryStatus.FAILED);
        assertThat(DeliveryStatus.fromString("PENDING")).isEqualTo(DeliveryStatus.PENDING);
    }

    @Test
    void shouldDeserializeFromJsonPayloads() throws Exception {
        record StatusPayload(DeliveryStatus status) {}

        StatusPayload inTransit = objectMapper.readValue("{\"status\":\"IN_TRANSIT\"}", StatusPayload.class);
        assertThat(inTransit.status()).isEqualTo(DeliveryStatus.EN_ROUTE);

        StatusPayload assigned = objectMapper.readValue("{\"status\":\"ASSIGNED\"}", StatusPayload.class);
        assertThat(assigned.status()).isEqualTo(DeliveryStatus.RIDER_ASSIGNED);

        StatusPayload nearDest = objectMapper.readValue("{\"status\":\"NEAR_DESTINATION\"}", StatusPayload.class);
        assertThat(nearDest.status()).isEqualTo(DeliveryStatus.NEAR_DESTINATION);
    }

    @Test
    void shouldValidateActiveAndTerminalFlags() {
        assertThat(DeliveryStatus.RIDER_ASSIGNED.isActive()).isTrue();
        assertThat(DeliveryStatus.ASSIGNED.isActive()).isTrue();
        assertThat(DeliveryStatus.EN_ROUTE.isActive()).isTrue();
        assertThat(DeliveryStatus.IN_TRANSIT.isActive()).isTrue();
        assertThat(DeliveryStatus.DELIVERED.isActive()).isFalse();

        assertThat(DeliveryStatus.DELIVERED.isTerminal()).isTrue();
        assertThat(DeliveryStatus.FAILED.isTerminal()).isTrue();
        assertThat(DeliveryStatus.EN_ROUTE.isTerminal()).isFalse();
    }
}
