package com.instacommerce.contracts.schemas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaValidityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldValidateAllSchemasConformToDraft07AndStandards() throws IOException {
        Path schemasPath = Path.of("src/main/resources/schemas");
        if (!Files.exists(schemasPath)) {
            schemasPath = Path.of("contracts/src/main/resources/schemas");
        }
        assertThat(schemasPath).exists();

        List<String> errors = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(schemasPath)) {
            List<Path> schemaFiles = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .toList();

            assertThat(schemaFiles).isNotEmpty();

            for (Path file : schemaFiles) {
                try {
                    JsonNode root = objectMapper.readTree(file.toFile());

                    // Check $schema
                    JsonNode schemaNode = root.get("$schema");
                    if (schemaNode == null || !schemaNode.asText().contains("draft-07")) {
                        errors.add(file.getFileName() + ": missing or invalid $schema draft-07 declaration");
                    }

                    // Check title
                    JsonNode titleNode = root.get("title");
                    if (titleNode == null || titleNode.asText().isBlank()) {
                        errors.add(file.getFileName() + ": missing or empty title");
                    } else {
                        String expectedPrefix = file.getFileName().toString().split("\\.")[0];
                        if (!titleNode.asText().equals(expectedPrefix)) {
                            errors.add(file.getFileName() + ": title '" + titleNode.asText() + "' does not match file prefix '" + expectedPrefix + "'");
                        }
                    }

                    // Check type
                    JsonNode typeNode = root.get("type");
                    if (typeNode == null) {
                        errors.add(file.getFileName() + ": missing 'type'");
                    } else if (typeNode.asText().equals("string")) {
                        if (!root.has("enum")) {
                            errors.add(file.getFileName() + ": string type schema must define 'enum'");
                        }
                    } else if (!typeNode.asText().equals("object") && !typeNode.isArray()) {
                        errors.add(file.getFileName() + ": expected type 'object' or enum 'string'");
                    }

                    // Check required properties declared in properties
                    JsonNode reqNode = root.get("required");
                    JsonNode propsNode = root.get("properties");
                    if (reqNode != null && reqNode.isArray()) {
                        if (propsNode == null || !propsNode.isObject()) {
                            errors.add(file.getFileName() + ": has 'required' but missing 'properties'");
                        } else {
                            for (Iterator<JsonNode> it = reqNode.elements(); it.hasNext(); ) {
                                String reqField = it.next().asText();
                                if (!propsNode.has(reqField)) {
                                    errors.add(file.getFileName() + ": required field '" + reqField + "' not defined in properties");
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    errors.add(file.getFileName() + ": JSON parsing failure - " + e.getMessage());
                }
            }
        }

        assertThat(errors)
            .withFailMessage("Found schema validity errors:\n%s", String.join("\n", errors))
            .isEmpty();
    }
}
