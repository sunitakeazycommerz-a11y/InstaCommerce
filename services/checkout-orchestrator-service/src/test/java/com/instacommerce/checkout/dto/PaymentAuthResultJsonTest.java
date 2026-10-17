package com.instacommerce.checkout.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentAuthResultJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("deserializes successfully when payment-service returns {paymentId, status}")
    void shouldDeserializeFromStatusPayload() throws Exception {
        String json = """
            {
                "paymentId": "550e8400-e29b-41d4-a716-446655440000",
                "status": "AUTHORIZED"
            }
            """;

        PaymentAuthResult result = objectMapper.readValue(json, PaymentAuthResult.class);

        assertThat(result.paymentId()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(result.authorized()).isTrue();
        assertThat(result.declineReason()).isNull();
    }

    @Test
    @DisplayName("deserializes successfully when payload has explicit authorized=false and declineReason")
    void shouldDeserializeFromExplicitAuthorizedPayload() throws Exception {
        String json = """
            {
                "paymentId": "550e8400-e29b-41d4-a716-446655440000",
                "authorized": false,
                "declineReason": "insufficient_funds"
            }
            """;

        PaymentAuthResult result = objectMapper.readValue(json, PaymentAuthResult.class);

        assertThat(result.paymentId()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(result.authorized()).isFalse();
        assertThat(result.declineReason()).isEqualTo("insufficient_funds");
    }
}
