package com.instacommerce.contracts.topics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TopicSchemaAlignmentTest {

    @Test
    @DisplayName("Every domain topic ending in .events in TopicNames must have a corresponding schema directory")
    void everyDomainTopicMustHaveCorrespondingSchemaDirectory() throws IllegalAccessException, IOException {
        Path schemasPath = Path.of("src/main/resources/schemas");
        if (!Files.exists(schemasPath)) {
            schemasPath = Path.of("contracts/src/main/resources/schemas");
        }
        assertThat(schemasPath)
                .withFailMessage("Schemas directory not found at %s", schemasPath)
                .exists();

        // Collect all schema directory names
        Set<String> schemaDirs = new HashSet<>();
        try (Stream<Path> stream = Files.list(schemasPath)) {
            stream.filter(Files::isDirectory)
                  .map(p -> p.getFileName().toString())
                  .forEach(schemaDirs::add);
        }

        List<String> missingSchemaTopics = new ArrayList<>();
        Set<String> checkedTopics = new HashSet<>();

        Field[] fields = TopicNames.class.getDeclaredFields();
        for (Field field : fields) {
            int modifiers = field.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)
                    && field.getType().equals(String.class)) {
                String topicValue = (String) field.get(null);
                if (topicValue != null && topicValue.endsWith(".events")) {
                    if (checkedTopics.add(topicValue)) {
                        // Topic format: "<domain>.events" or "<domain>.<subdomain>.events"
                        String domain = topicValue.substring(0, topicValue.indexOf(".events"));
                        if ("promotion".equals(domain)) {
                            domain = "pricing";
                        } else if (domain.contains(".")) {
                            domain = domain.substring(0, domain.indexOf("."));
                        }
                        
                        // Check if schema directory exists and has at least one schema file
                        Path domainPath = schemasPath.resolve(domain);
                        if (!Files.exists(domainPath) || !Files.isDirectory(domainPath)) {
                            missingSchemaTopics.add(topicValue + " (expected directory: " + domain + ")");
                        } else {
                            try (Stream<Path> files = Files.list(domainPath)) {
                                boolean hasSchemaFile = files.anyMatch(p -> p.getFileName().toString().endsWith(".json"));
                                if (!hasSchemaFile) {
                                    missingSchemaTopics.add(topicValue + " (directory " + domain + " has no .json schemas)");
                                }
                            }
                        }
                    }
                }
            }
        }

        assertThat(missingSchemaTopics)
                .withFailMessage("The following domain topics in TopicNames.java lack schema definitions: %s", missingSchemaTopics)
                .isEmpty();
    }
}
