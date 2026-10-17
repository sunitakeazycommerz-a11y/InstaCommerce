package com.instacommerce.fulfillment.domain.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryStatusTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldParseAllCanonicalAndLegacyStatuses() {
        assertThat(DeliveryStatus.fromString("PENDING")).isEqualTo(DeliveryStatus.PENDING);
        assertThat(DeliveryStatus.fromString("ASSIGNED")).isEqualTo(DeliveryStatus.ASSIGNED);
        assertThat(DeliveryStatus.fromString("RIDER_ASSIGNED")).isEqualTo(DeliveryStatus.ASSIGNED);
        assertThat(DeliveryStatus.fromString("PICKED_UP")).isEqualTo(DeliveryStatus.PICKED_UP);
        assertThat(DeliveryStatus.fromString("IN_TRANSIT")).isEqualTo(DeliveryStatus.IN_TRANSIT);
        assertThat(DeliveryStatus.fromString("EN_ROUTE")).isEqualTo(DeliveryStatus.IN_TRANSIT);
        assertThat(DeliveryStatus.fromString("NEAR_DESTINATION")).isEqualTo(DeliveryStatus.NEAR_DESTINATION);
        assertThat(DeliveryStatus.fromString("DELIVERED")).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(DeliveryStatus.fromString("FAILED")).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    void shouldDeserializeFromJsonPayloads() throws Exception {
        record StatusHolder(DeliveryStatus status) {}

        StatusHolder enRoute = objectMapper.readValue("{\"status\":\"EN_ROUTE\"}", StatusHolder.class);
        assertThat(enRoute.status()).isEqualTo(DeliveryStatus.IN_TRANSIT);

        StatusHolder nearDest = objectMapper.readValue("{\"status\":\"NEAR_DESTINATION\"}", StatusHolder.class);
        assertThat(nearDest.status()).isEqualTo(DeliveryStatus.NEAR_DESTINATION);

        StatusHolder riderAssigned = objectMapper.readValue("{\"status\":\"RIDER_ASSIGNED\"}", StatusHolder.class);
        assertThat(riderAssigned.status()).isEqualTo(DeliveryStatus.ASSIGNED);
    }

    @Test
    void shouldCorrectlyIdentifyStatusPredicates() {
        assertThat(DeliveryStatus.ASSIGNED.isAssigned()).isTrue();
        assertThat(DeliveryStatus.RIDER_ASSIGNED.isAssigned()).isTrue();
        assertThat(DeliveryStatus.PICKED_UP.isAssigned()).isFalse();

        assertThat(DeliveryStatus.IN_TRANSIT.isInTransit()).isTrue();
        assertThat(DeliveryStatus.EN_ROUTE.isInTransit()).isTrue();
        assertThat(DeliveryStatus.NEAR_DESTINATION.isInTransit()).isTrue();
        assertThat(DeliveryStatus.DELIVERED.isInTransit()).isFalse();

        assertThat(DeliveryStatus.DELIVERED.isTerminal()).isTrue();
        assertThat(DeliveryStatus.FAILED.isTerminal()).isTrue();
        assertThat(DeliveryStatus.IN_TRANSIT.isTerminal()).isFalse();
    }
}
