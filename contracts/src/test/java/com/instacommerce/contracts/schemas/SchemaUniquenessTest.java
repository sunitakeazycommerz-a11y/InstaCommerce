package com.instacommerce.contracts.schemas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaUniquenessTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldHaveZeroDuplicateSchemaTitlesAcrossDomains() throws IOException {
        Path schemasPath = Path.of("src/main/resources/schemas");
        if (!Files.exists(schemasPath)) {
            // In case running from root monorepo directory
            schemasPath = Path.of("contracts/src/main/resources/schemas");
        }
        assertThat(schemasPath).exists();

        Map<String, List<String>> titlesToFiles = new HashMap<>();

        try (Stream<Path> stream = Files.walk(schemasPath)) {
            List<Path> schemaFiles = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .toList();

            assertThat(schemaFiles).isNotEmpty();

            for (Path file : schemaFiles) {
                JsonNode root = objectMapper.readTree(file.toFile());
                JsonNode titleNode = root.get("title");
                if (titleNode != null && !titleNode.asText().isBlank()) {
                    String title = titleNode.asText();
                    titlesToFiles.computeIfAbsent(title, k -> new ArrayList<>())
                        .add(file.toString());
                }
            }
        }

        Map<String, List<String>> duplicates = new HashMap<>();
        titlesToFiles.forEach((title, files) -> {
            if (files.size() > 1) {
                duplicates.put(title, files);
            }
        });

        assertThat(duplicates)
            .withFailMessage("Found duplicate schema titles across directories: %s", duplicates)
            .isEmpty();
    }
}
