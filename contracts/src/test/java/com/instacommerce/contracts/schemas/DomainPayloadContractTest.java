package com.instacommerce.contracts.schemas;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DomainPayloadContractTest {

    static Stream<Arguments> validPayloads() {
        return DomainPayloadFixtures.validPayloads();
    }

    @ParameterizedTest(name = "{0} accepts valid producer payload")
    @MethodSource("validPayloads")
    void shouldAcceptValidDomainPayload(String schemaRelPath, Map<String, Object> payload) throws IOException {
        JsonNode schema = JsonSchemaValidator.MAPPER.readTree(
                JsonSchemaValidator.resolveSchemasRoot().resolve(schemaRelPath).toFile());
        JsonNode instance = JsonSchemaValidator.MAPPER.valueToTree(payload);
        List<String> errors = JsonSchemaValidator.validate(schema, instance);
        assertThat(errors)
                .withFailMessage("Payload failed schema %s: %s", schemaRelPath, errors)
                .isEmpty();
    }

    @Test
    @DisplayName("Schemas reject missing required fields, bad UUIDs, negative money, and invalid enums")
    void shouldRejectInvalidDomainPayloads() throws IOException {
        Path root = JsonSchemaValidator.resolveSchemasRoot();

        JsonNode refundSchema = JsonSchemaValidator.MAPPER.readTree(
                root.resolve("orders/OrderPartiallyRefunded.v1.json").toFile());
        JsonNode negativeRefund = JsonSchemaValidator.MAPPER.valueToTree(Map.of(
                "orderId", "not-a-uuid",
                "refundCents", -10,
                "walletRefundCents", -1,
                "priceDeltaCents", -10,
                "reason", ""));
        assertThat(JsonSchemaValidator.validate(refundSchema, negativeRefund))
                .anyMatch(e -> e.contains("orderId"))
                .anyMatch(e -> e.contains("refundCents"))
                .anyMatch(e -> e.contains("walletRefundCents"))
                .anyMatch(e -> e.contains("reason"));

        JsonNode podSchema = JsonSchemaValidator.MAPPER.readTree(
                root.resolve("fulfillment/ProofOfDeliveryUploaded.v1.json").toFile());
        JsonNode badFormatPod = JsonSchemaValidator.MAPPER.valueToTree(Map.of(
                "orderId", DomainPayloadFixtures.UUID_A,
                "photoUrl", "https://cdn.instacommerce.internal/pod/ord-1.jpg",
                "lifecycleDays", 30,
                "format", "JPEG"));
        assertThat(JsonSchemaValidator.validate(podSchema, badFormatPod))
                .anyMatch(e -> e.contains("enum"));

        JsonNode geofenceSchema = JsonSchemaValidator.MAPPER.readTree(
                root.resolve("rider/RiderGeofenceTransition.v1.json").toFile());
        JsonNode missingH3 = JsonSchemaValidator.MAPPER.valueToTree(Map.of(
                "rider_id", "rider-1",
                "zone_id", "zone-1"));
        assertThat(JsonSchemaValidator.validate(geofenceSchema, missingH3))
                .anyMatch(e -> e.contains("missing required field"));
    }

    @Test
    @DisplayName("OrderPlaced accepts nullable storageTemperature and rejects invalid item fields")
    void shouldValidateOrderPlacedLineItemExtensions() throws IOException {
        Path root = JsonSchemaValidator.resolveSchemasRoot();
        JsonNode orderPlacedSchema = JsonSchemaValidator.MAPPER.readTree(
                root.resolve("orders/OrderPlaced.v1.json").toFile());

        Map<String, Object> nullableItem = new HashMap<>();
        nullableItem.put("productId", DomainPayloadFixtures.UUID_C);
        nullableItem.put("productName", "Frozen Peas 500g");
        nullableItem.put("quantity", 1);
        nullableItem.put("unitPriceCents", 120);
        nullableItem.put("lineTotalCents", 120);
        nullableItem.put("coldChain", true);
        nullableItem.put("fragile", false);
        nullableItem.put("storageTemperature", null);
        nullableItem.put("tareWeightGrams", 0);

        JsonNode validWithNullTemp = JsonSchemaValidator.MAPPER.valueToTree(Map.of(
                "orderId", DomainPayloadFixtures.UUID_A,
                "userId", DomainPayloadFixtures.UUID_B,
                "items", List.of(nullableItem),
                "totalCents", 120,
                "currency", "INR",
                "placedAt", DomainPayloadFixtures.NOW_ISO));
        assertThat(JsonSchemaValidator.validate(orderPlacedSchema, validWithNullTemp)).isEmpty();

        Map<String, Object> badItem = new HashMap<>(nullableItem);
        badItem.put("coldChain", "yes");
        badItem.put("tareWeightGrams", -5);
        JsonNode invalid = JsonSchemaValidator.MAPPER.valueToTree(Map.of(
                "orderId", DomainPayloadFixtures.UUID_A,
                "userId", DomainPayloadFixtures.UUID_B,
                "items", List.of(badItem),
                "totalCents", 120,
                "currency", "INR",
                "placedAt", DomainPayloadFixtures.NOW_ISO));
        assertThat(JsonSchemaValidator.validate(orderPlacedSchema, invalid))
                .anyMatch(e -> e.contains("items[0].coldChain"))
                .anyMatch(e -> e.contains("items[0].tareWeightGrams"));
    }

    @Test
    @DisplayName("OrderPacked, OrderCancelled, OrderFailed, and OrderRefunded enforce additive wallet and weight constraints")
    void shouldValidateOrderPackedAndRefundExtensions() throws IOException {
        Path root = JsonSchemaValidator.resolveSchemasRoot();

        JsonNode packedSchema = JsonSchemaValidator.MAPPER.readTree(
                root.resolve("fulfillment/OrderPacked.v1.json").toFile());
        JsonNode badPacked = JsonSchemaValidator.MAPPER.valueToTree(Map.of(
                "orderId", DomainPayloadFixtures.UUID_A,
                "userId", DomainPayloadFixtures.UUID_B,
                "packedAt", DomainPayloadFixtures.NOW_ISO,
                "orderWeightGrams", -1,
                "coldChain", "true"));
        assertThat(JsonSchemaValidator.validate(packedSchema, badPacked))
                .anyMatch(e -> e.contains("orderWeightGrams"))
                .anyMatch(e -> e.contains("coldChain"));

        for (String relPath : List.of(
                "orders/OrderCancelled.v1.json",
                "orders/OrderFailed.v1.json",
                "orders/OrderRefunded.v1.json")) {
            JsonNode schema = JsonSchemaValidator.MAPPER.readTree(root.resolve(relPath).toFile());
            JsonNode negativeMoney = JsonSchemaValidator.MAPPER.valueToTree(Map.of(
                    "orderId", DomainPayloadFixtures.UUID_A,
                    "reason", "test",
                    "walletAmountCents", -1,
                    "walletRefundCents", -2,
                    "refundedCents", -3));
            assertThat(JsonSchemaValidator.validate(schema, negativeMoney))
                    .anyMatch(e -> e.contains("walletAmountCents"))
                    .anyMatch(e -> e.contains("walletRefundCents"))
                    .anyMatch(e -> e.contains("refundedCents"));
        }
    }

    @Test
    @DisplayName("Validator enforces additionalProperties: false, pattern, scalar array items, and numeric enum/bounds recursively")
    void shouldEnforceStrictRecursiveSchemaConstraints() throws IOException {
        JsonNode strictSchema = JsonSchemaValidator.MAPPER.readTree("""
                {
                  "type": "object",
                  "required": ["code", "weights"],
                  "additionalProperties": false,
                  "properties": {
                    "code": { "type": "string", "pattern": "^ORD-[0-9]{4}$" },
                    "tier": { "type": "integer", "enum": [1, 2, 3] },
                    "weights": {
                      "type": "array",
                      "items": { "type": "integer", "minimum": 1, "maximum": 50000 }
                    }
                  }
                }
                """);

        JsonNode valid = JsonSchemaValidator.MAPPER.valueToTree(Map.of(
                "code", "ORD-1042",
                "tier", 2,
                "weights", List.of(250, 1200)));
        assertThat(JsonSchemaValidator.validate(strictSchema, valid)).isEmpty();

        JsonNode invalid = JsonSchemaValidator.MAPPER.valueToTree(Map.of(
                "code", "BAD",
                "tier", 9,
                "weights", List.of(0, 60000),
                "unexpected", true));
        assertThat(JsonSchemaValidator.validate(strictSchema, invalid))
                .anyMatch(e -> e.contains("code") && e.contains("pattern"))
                .anyMatch(e -> e.contains("tier") && e.contains("enum"))
                .anyMatch(e -> e.contains("weights[0]") && e.contains("minimum"))
                .anyMatch(e -> e.contains("weights[1]") && e.contains("maximum"))
                .anyMatch(e -> e.contains("unexpected") && e.contains("additional property"));
    }
}
