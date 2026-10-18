package com.instacommerce.contracts.schemas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("All existing production schemas must be self-compatible")
    void allSchemasMustBeSelfCompatible() throws IOException {
        Path schemasPath = Path.of("src/main/resources/schemas");
        if (!Files.exists(schemasPath)) {
            schemasPath = Path.of("contracts/src/main/resources/schemas");
        }
        assertThat(schemasPath).exists();

        List<Path> schemaFiles;
        try (Stream<Path> stream = Files.walk(schemasPath)) {
            schemaFiles = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .toList();
        }
        assertThat(schemaFiles).isNotEmpty();

        List<SchemaCompatibilityValidator.CompatibilityViolation> allViolations = new ArrayList<>();
        for (Path schemaFile : schemaFiles) {
            JsonNode schemaNode = objectMapper.readTree(schemaFile.toFile());
            List<SchemaCompatibilityValidator.CompatibilityViolation> violations =
                SchemaCompatibilityValidator.validate(schemaNode, schemaNode, schemaFile.getFileName().toString());
            allViolations.addAll(violations);
        }

        assertThat(allViolations).isEmpty();
    }

    @Test
    @DisplayName("Should detect violation when required field is removed")
    void shouldDetectRemovedRequiredField() throws Exception {
        String oldSchemaJson = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "required": ["orderId", "userId"],
              "properties": {
                "orderId": { "type": "string" },
                "userId": { "type": "string" }
              }
            }
            """;
        String newSchemaJson = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "required": ["orderId"],
              "properties": {
                "orderId": { "type": "string" },
                "userId": { "type": "string" }
              }
            }
            """;

        JsonNode oldNode = objectMapper.readTree(oldSchemaJson);
        JsonNode newNode = objectMapper.readTree(newSchemaJson);

        List<SchemaCompatibilityValidator.CompatibilityViolation> violations =
            SchemaCompatibilityValidator.validate(oldNode, newNode, "OrderEvent.v1.json");

        assertThat(violations)
            .hasSize(1)
            .anyMatch(v -> v.type() == SchemaCompatibilityValidator.ViolationType.REQUIRED_FIELD_REMOVED
                && v.propertyPath().contains("userId"));
    }

    @Test
    @DisplayName("Should detect violation when newly added field is required (breaks old producers)")
    void shouldDetectNewlyAddedRequiredField() throws Exception {
        String oldSchemaJson = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "required": ["orderId"],
              "properties": {
                "orderId": { "type": "string" }
              }
            }
            """;
        String newSchemaJson = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "required": ["orderId", "tenantId"],
              "properties": {
                "orderId": { "type": "string" },
                "tenantId": { "type": "string" }
              }
            }
            """;

        JsonNode oldNode = objectMapper.readTree(oldSchemaJson);
        JsonNode newNode = objectMapper.readTree(newSchemaJson);

        List<SchemaCompatibilityValidator.CompatibilityViolation> violations =
            SchemaCompatibilityValidator.validate(oldNode, newNode, "OrderEvent.v1.json");

        assertThat(violations)
            .hasSize(1)
            .anyMatch(v -> v.type() == SchemaCompatibilityValidator.ViolationType.REQUIRED_FIELD_ADDED
                && v.propertyPath().contains("tenantId"));
    }

    @Test
    @DisplayName("Should detect violation when property type is mutated")
    void shouldDetectPropertyTypeMutation() throws Exception {
        String oldSchemaJson = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {
                "amount": { "type": "integer" }
              }
            }
            """;
        String newSchemaJson = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {
                "amount": { "type": "string" }
              }
            }
            """;

        JsonNode oldNode = objectMapper.readTree(oldSchemaJson);
        JsonNode newNode = objectMapper.readTree(newSchemaJson);

        List<SchemaCompatibilityValidator.CompatibilityViolation> violations =
            SchemaCompatibilityValidator.validate(oldNode, newNode, "PaymentEvent.v1.json");

        assertThat(violations)
            .hasSize(1)
            .anyMatch(v -> v.type() == SchemaCompatibilityValidator.ViolationType.PROPERTY_TYPE_CHANGED
                && v.propertyPath().contains("amount"));
    }

    @Test
    @DisplayName("Should detect violation when enum value is removed")
    void shouldDetectRemovedEnumValue() throws Exception {
        String oldSchemaJson = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {
                "status": {
                  "type": "string",
                  "enum": ["PENDING", "PROCESSING", "COMPLETED", "FAILED"]
                }
              }
            }
            """;
        String newSchemaJson = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {
                "status": {
                  "type": "string",
                  "enum": ["PENDING", "COMPLETED", "FAILED"]
                }
              }
            }
            """;

        JsonNode oldNode = objectMapper.readTree(oldSchemaJson);
        JsonNode newNode = objectMapper.readTree(newSchemaJson);

        List<SchemaCompatibilityValidator.CompatibilityViolation> violations =
            SchemaCompatibilityValidator.validate(oldNode, newNode, "StatusEvent.v1.json");

        assertThat(violations)
            .hasSize(1)
            .anyMatch(v -> v.type() == SchemaCompatibilityValidator.ViolationType.ENUM_VALUE_REMOVED
                && v.message().contains("PROCESSING"));
    }

    @Test
    @DisplayName("Should permit non-breaking schema evolutions: optional field addition and enum extension")
    void shouldPermitNonBreakingEvolutions() throws Exception {
        String oldSchemaJson = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "required": ["orderId"],
              "properties": {
                "orderId": { "type": "string" },
                "status": {
                  "type": "string",
                  "enum": ["PENDING", "COMPLETED"]
                }
              }
            }
            """;
        String newSchemaJson = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "required": ["orderId"],
              "properties": {
                "orderId": { "type": "string" },
                "notes": { "type": "string" },
                "status": {
                  "type": "string",
                  "enum": ["PENDING", "COMPLETED", "CANCELLED"]
                }
              }
            }
            """;

        JsonNode oldNode = objectMapper.readTree(oldSchemaJson);
        JsonNode newNode = objectMapper.readTree(newSchemaJson);

        List<SchemaCompatibilityValidator.CompatibilityViolation> violations =
            SchemaCompatibilityValidator.validate(oldNode, newNode, "OrderEvent.v1.json");

        assertThat(violations).isEmpty();
    }
}
