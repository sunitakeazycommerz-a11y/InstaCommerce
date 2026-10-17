package com.instacommerce.checkout.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutDtoJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesCartValidationResultWithDefaults() throws Exception {
        String json = """
            {
              "userId": "11111111-1111-1111-1111-111111111111",
              "items": [
                {
                  "productId": "22222222-2222-2222-2222-222222222222",
                  "quantity": 2,
                  "unitPriceCents": 500
                }
              ]
            }
            """;
        CartValidationResult result = objectMapper.readValue(json, CartValidationResult.class);
        assertThat(result.userId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(result.items()).hasSize(1);
        assertThat(result.valid()).isTrue();
        assertThat(result.storeId()).isEqualTo("00000000-0000-0000-0000-000000000001");
    }

    @Test
    void deserializesInventoryReservationResultWithDefaults() throws Exception {
        String json = """
            {
              "reservationId": "res-9876",
              "expiresAt": "2026-10-17T18:00:00Z"
            }
            """;
        InventoryReservationResult result = objectMapper.readValue(json, InventoryReservationResult.class);
        assertThat(result.reservationId()).isEqualTo("res-9876");
        assertThat(result.reserved()).isTrue();
    }

    @Test
    void deserializesPricingResultWithDefaults() throws Exception {
        String json = """
            {
              "subtotalCents": 1000,
              "discountCents": 100,
              "totalCents": 900,
              "quoteId": "quote-1234",
              "quoteToken": "token-5678"
            }
            """;
        PricingResult result = objectMapper.readValue(json, PricingResult.class);
        assertThat(result.subtotalCents()).isEqualTo(1000);
        assertThat(result.discountCents()).isEqualTo(100);
        assertThat(result.deliveryFeeCents()).isEqualTo(0L);
        assertThat(result.totalCents()).isEqualTo(900);
        assertThat(result.currency()).isEqualTo("INR");
        assertThat(result.quoteId()).isEqualTo("quote-1234");
    }
}
