package com.instacommerce.contracts.schemas;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Validates backwards and forwards compatibility between JSON Schema versions
 * adhering to Confluent/CloudEvents FULL compatibility rules:
 * 1. Old consumers can read messages written with new schemas.
 * 2. New consumers can read messages written with old schemas.
 */
public final class SchemaCompatibilityValidator {

    private SchemaCompatibilityValidator() {}

    public record CompatibilityViolation(
        String schemaName,
        String propertyPath,
        ViolationType type,
        String message
    ) {
        @Override
        public String toString() {
            return String.format("[%s] %s at '%s': %s", schemaName, type, propertyPath, message);
        }
    }

    public enum ViolationType {
        REQUIRED_FIELD_REMOVED,
        REQUIRED_FIELD_ADDED,
        PROPERTY_TYPE_CHANGED,
        ENUM_VALUE_REMOVED,
        ROOT_TYPE_CHANGED,
        ADDITIONAL_PROPERTIES_RESTRICTED
    }

    /**
     * Checks if newSchema is backwards-compatible with oldSchema.
     *
     * @param oldSchema baseline/previous JSON schema
     * @param newSchema target/candidate JSON schema
     * @param schemaName identifier or file path for error reporting
     * @return list of compatibility violations (empty if fully compatible)
     */
    public static List<CompatibilityViolation> validate(JsonNode oldSchema, JsonNode newSchema, String schemaName) {
        List<CompatibilityViolation> violations = new ArrayList<>();
        if (oldSchema == null || newSchema == null) {
            return violations;
        }

        compareSchemaNodes(oldSchema, newSchema, "$", schemaName, violations);
        return violations;
    }

    private static void compareSchemaNodes(
        JsonNode oldNode,
        JsonNode newNode,
        String path,
        String schemaName,
        List<CompatibilityViolation> violations
    ) {
        // 1. Root type check
        if (oldNode.has("type") && newNode.has("type")) {
            String oldType = oldNode.get("type").asText();
            String newType = newNode.get("type").asText();
            if (!oldType.equals(newType)) {
                violations.add(new CompatibilityViolation(
                    schemaName,
                    path,
                    ViolationType.ROOT_TYPE_CHANGED,
                    String.format("Schema node type changed from '%s' to '%s'", oldType, newType)
                ));
                return;
            }
        }

        // 2. additionalProperties restriction check
        boolean oldAllowsAdditional = !oldNode.has("additionalProperties") || oldNode.get("additionalProperties").asBoolean(true);
        boolean newAllowsAdditional = !newNode.has("additionalProperties") || newNode.get("additionalProperties").asBoolean(true);
        if (oldAllowsAdditional && !newAllowsAdditional) {
            violations.add(new CompatibilityViolation(
                schemaName,
                path,
                ViolationType.ADDITIONAL_PROPERTIES_RESTRICTED,
                "additionalProperties was restricted from true/default to false"
            ));
        }

        // 3. Required fields backwards and forwards compatibility
        Set<String> oldRequired = extractStringSet(oldNode.get("required"));
        Set<String> newRequired = extractStringSet(newNode.get("required"));

        // Rule: Existing required fields cannot be removed from new schema (old consumers require it)
        for (String req : oldRequired) {
            if (!newRequired.contains(req)) {
                violations.add(new CompatibilityViolation(
                    schemaName,
                    path + "." + req,
                    ViolationType.REQUIRED_FIELD_REMOVED,
                    String.format("Required field '%s' was removed or made optional", req)
                ));
            }
        }

        // Rule: New fields added to new schema cannot be required (old messages do not have it)
        JsonNode oldProperties = oldNode.get("properties");
        for (String req : newRequired) {
            if (oldProperties == null || !oldProperties.has(req)) {
                // Brand new property marked as required
                violations.add(new CompatibilityViolation(
                    schemaName,
                    path + "." + req,
                    ViolationType.REQUIRED_FIELD_ADDED,
                    String.format("Newly introduced field '%s' cannot be required for backwards compatibility", req)
                ));
            }
        }

        // 4. Properties checks
        if (oldProperties != null && oldProperties.isObject() && newNode.has("properties")) {
            JsonNode newProperties = newNode.get("properties");
            for (Iterator<String> it = oldProperties.fieldNames(); it.hasNext(); ) {
                String fieldName = it.next();
                JsonNode oldProp = oldProperties.get(fieldName);
                if (newProperties.has(fieldName)) {
                    JsonNode newProp = newProperties.get(fieldName);
                    compareProperty(oldProp, newProp, path + "." + fieldName, schemaName, violations);
                } else if (!newAllowsAdditional) {
                    // Property removed and additionalProperties is false
                    violations.add(new CompatibilityViolation(
                        schemaName,
                        path + "." + fieldName,
                        ViolationType.REQUIRED_FIELD_REMOVED,
                        String.format("Field '%s' was deleted in a schema with additionalProperties: false", fieldName)
                    ));
                }
            }
        }

        // 5. Enum values check at current level
        checkEnumCompatibility(oldNode, newNode, path, schemaName, violations);
    }

    private static void compareProperty(
        JsonNode oldProp,
        JsonNode newProp,
        String path,
        String schemaName,
        List<CompatibilityViolation> violations
    ) {
        // Compare types
        if (oldProp.has("type") && newProp.has("type")) {
            String oldType = oldProp.get("type").asText();
            String newType = newProp.get("type").asText();
            if (!oldType.equals(newType)) {
                violations.add(new CompatibilityViolation(
                    schemaName,
                    path,
                    ViolationType.PROPERTY_TYPE_CHANGED,
                    String.format("Field type changed from '%s' to '%s'", oldType, newType)
                ));
                return;
            }
        }

        // Enum compatibility
        checkEnumCompatibility(oldProp, newProp, path, schemaName, violations);

        // Nested object recursion
        if (oldProp.has("properties") && newProp.has("properties")) {
            compareSchemaNodes(oldProp, newProp, path, schemaName, violations);
        }

        // Array items recursion
        if (oldProp.has("items") && newProp.has("items")) {
            compareProperty(oldProp.get("items"), newProp.get("items"), path + "[]", schemaName, violations);
        }
    }

    private static void checkEnumCompatibility(
        JsonNode oldNode,
        JsonNode newNode,
        String path,
        String schemaName,
        List<CompatibilityViolation> violations
    ) {
        if (oldNode.has("enum")) {
            if (!newNode.has("enum")) {
                // Enum removed, types must match
                return;
            }
            Set<String> oldEnumValues = extractStringSet(oldNode.get("enum"));
            Set<String> newEnumValues = extractStringSet(newNode.get("enum"));
            for (String oldVal : oldEnumValues) {
                if (!newEnumValues.contains(oldVal)) {
                    violations.add(new CompatibilityViolation(
                        schemaName,
                        path,
                        ViolationType.ENUM_VALUE_REMOVED,
                        String.format("Enum value '%s' was removed from allowed set", oldVal)
                    ));
                }
            }
        }
    }

    private static Set<String> extractStringSet(JsonNode arrayNode) {
        Set<String> set = new HashSet<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                set.add(item.asText());
            }
        }
        return set;
    }
}
