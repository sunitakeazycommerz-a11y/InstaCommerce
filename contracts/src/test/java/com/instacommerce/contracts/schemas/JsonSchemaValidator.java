package com.instacommerce.contracts.schemas;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

final class JsonSchemaValidator {

    static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private JsonSchemaValidator() {
    }

    static Path resolveSchemasRoot() {
        Path path = Path.of("src/main/resources/schemas");
        if (!Files.exists(path)) {
            path = Path.of("contracts/src/main/resources/schemas");
        }
        assertThat(path).exists();
        return path;
    }

    static List<String> validate(JsonNode schema, JsonNode instance) {
        List<String> errors = new ArrayList<>();
        validateNode("$", schema, instance, errors);
        return errors;
    }

    private static void validateNode(String path, JsonNode schema, JsonNode instance, List<String> errors) {
        if (schema == null || schema.isNull()) {
            return;
        }
        if (schema.isBoolean()) {
            if (!schema.asBoolean()) {
                errors.add(path + ": schema forbids any value");
            }
            return;
        }

        validateCombinators(path, schema, instance, errors);

        if (instance == null || instance.isNull()) {
            JsonNode typeNode = schema.get("type");
            if (typeNode != null && !allowsType(typeNode, "null")) {
                errors.add(path + ": null not allowed");
            }
            validateEnum(path, schema, instance, errors);
            return;
        }

        JsonNode typeNode = schema.get("type");
        if (typeNode != null && !matchesSchemaType(typeNode, instance)) {
            errors.add(path + ": unexpected type " + instance.getNodeType() + " for schema " + typeNode);
            return;
        }

        validateEnum(path, schema, instance, errors);

        if (instance.isTextual()) {
            validateStringConstraints(path, schema, instance.asText(), errors);
        } else if (instance.isNumber()) {
            validateNumericBounds(path, schema, instance.decimalValue(), errors);
        } else if (instance.isArray()) {
            validateArray(path, schema, instance, errors);
        } else if (instance.isObject()) {
            validateObject(path, schema, instance, errors);
        }
    }

    private static void validateCombinators(String path, JsonNode schema, JsonNode instance, List<String> errors) {
        if (schema.has("allOf")) {
            for (JsonNode sub : schema.get("allOf")) {
                validateNode(path, sub, instance, errors);
            }
        }
        if (schema.has("anyOf")) {
            boolean anyMatched = false;
            for (JsonNode sub : schema.get("anyOf")) {
                List<String> branchErrors = new ArrayList<>();
                validateNode(path, sub, instance, branchErrors);
                if (branchErrors.isEmpty()) {
                    anyMatched = true;
                    break;
                }
            }
            if (!anyMatched) {
                errors.add(path + ": does not match anyOf schema");
            }
        }
        if (schema.has("oneOf")) {
            int matches = 0;
            for (JsonNode sub : schema.get("oneOf")) {
                List<String> branchErrors = new ArrayList<>();
                validateNode(path, sub, instance, branchErrors);
                if (branchErrors.isEmpty()) {
                    matches++;
                }
            }
            if (matches != 1) {
                errors.add(path + ": expected exactly 1 oneOf match, found " + matches);
            }
        }
    }

    private static void validateObject(String path, JsonNode schema, JsonNode instance, List<String> errors) {
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode req : required) {
                String fieldName = req.asText();
                if (!instance.has(fieldName)) {
                    errors.add(path + ": missing required field: " + fieldName);
                }
            }
        }

        JsonNode properties = schema.get("properties");
        JsonNode patternProperties = schema.get("patternProperties");
        JsonNode additionalProps = schema.get("additionalProperties");

        for (Iterator<Map.Entry<String, JsonNode>> it = instance.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String field = entry.getKey();
            JsonNode value = entry.getValue();
            String childPath = "$".equals(path) ? field : path + "." + field;

            boolean handled = false;
            if (properties != null && properties.isObject() && properties.has(field)) {
                validateNode(childPath, properties.get(field), value, errors);
                handled = true;
            }
            if (patternProperties != null && patternProperties.isObject()) {
                for (Iterator<Map.Entry<String, JsonNode>> pit = patternProperties.fields(); pit.hasNext(); ) {
                    Map.Entry<String, JsonNode> patEntry = pit.next();
                    if (Pattern.compile(patEntry.getKey()).matcher(field).find()) {
                        validateNode(childPath, patEntry.getValue(), value, errors);
                        handled = true;
                    }
                }
            }
            if (!handled && additionalProps != null) {
                if (additionalProps.isBoolean() && !additionalProps.asBoolean()) {
                    errors.add(childPath + ": additional property not allowed");
                } else if (additionalProps.isObject()) {
                    validateNode(childPath, additionalProps, value, errors);
                }
            }
        }
    }

    private static void validateArray(String path, JsonNode schema, JsonNode instance, List<String> errors) {
        if (schema.has("minItems") && instance.size() < schema.get("minItems").asInt()) {
            errors.add(path + ": array size " + instance.size() + " below minItems " + schema.get("minItems").asInt());
        }
        if (schema.has("maxItems") && instance.size() > schema.get("maxItems").asInt()) {
            errors.add(path + ": array size " + instance.size() + " above maxItems " + schema.get("maxItems").asInt());
        }
        if (schema.has("uniqueItems") && schema.get("uniqueItems").asBoolean()) {
            Set<JsonNode> seen = new HashSet<>();
            for (JsonNode elem : instance) {
                if (!seen.add(elem)) {
                    errors.add(path + ": array contains duplicate items");
                    break;
                }
            }
        }
        JsonNode itemsSchema = schema.get("items");
        if (itemsSchema == null) {
            return;
        }
        if (itemsSchema.isObject()) {
            int idx = 0;
            for (JsonNode elem : instance) {
                validateNode(path + "[" + idx + "]", itemsSchema, elem, errors);
                idx++;
            }
        } else if (itemsSchema.isArray()) {
            for (int idx = 0; idx < instance.size(); idx++) {
                if (idx < itemsSchema.size()) {
                    validateNode(path + "[" + idx + "]", itemsSchema.get(idx), instance.get(idx), errors);
                } else if (schema.has("additionalItems")) {
                    JsonNode addItems = schema.get("additionalItems");
                    if (addItems.isBoolean() && !addItems.asBoolean()) {
                        errors.add(path + "[" + idx + "]: additional tuple item not allowed");
                    } else if (addItems.isObject()) {
                        validateNode(path + "[" + idx + "]", addItems, instance.get(idx), errors);
                    }
                }
            }
        }
    }

    private static boolean matchesSchemaType(JsonNode typeNode, JsonNode instance) {
        if (instance.isTextual()) {
            return allowsType(typeNode, "string");
        }
        if (instance.isBoolean()) {
            return allowsType(typeNode, "boolean");
        }
        if (instance.isIntegralNumber()) {
            return allowsType(typeNode, "integer") || allowsType(typeNode, "number");
        }
        if (instance.isNumber()) {
            if (allowsType(typeNode, "number")) {
                return true;
            }
            if (allowsType(typeNode, "integer")) {
                BigDecimal bd = instance.decimalValue();
                return bd.stripTrailingZeros().scale() <= 0;
            }
            return false;
        }
        if (instance.isArray()) {
            return allowsType(typeNode, "array");
        }
        if (instance.isObject()) {
            return allowsType(typeNode, "object");
        }
        return false;
    }

    private static boolean allowsType(JsonNode typeNode, String target) {
        if (typeNode == null) {
            return true;
        }
        if (typeNode.isTextual()) {
            return target.equals(typeNode.asText());
        }
        if (typeNode.isArray()) {
            for (JsonNode option : typeNode) {
                if (target.equals(option.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void validateEnum(String path, JsonNode schema, JsonNode instance, List<String> errors) {
        JsonNode enumNode = schema.get("enum");
        if (enumNode == null || !enumNode.isArray()) {
            return;
        }
        for (JsonNode allowed : enumNode) {
            if (instance != null && instance.isNumber() && allowed.isNumber()) {
                if (instance.decimalValue().compareTo(allowed.decimalValue()) == 0) {
                    return;
                }
            } else if (allowed.equals(instance)) {
                return;
            }
        }
        errors.add(path + ": value " + instance + " not in enum");
    }

    private static void validateStringConstraints(String path, JsonNode schema, String value, List<String> errors) {
        if (schema.has("minLength") && value.length() < schema.get("minLength").asInt()) {
            errors.add(path + ": length below minLength");
        }
        if (schema.has("maxLength") && value.length() > schema.get("maxLength").asInt()) {
            errors.add(path + ": length above maxLength");
        }
        if (schema.has("pattern")) {
            String pattern = schema.get("pattern").asText();
            if (!Pattern.compile(pattern).matcher(value).find()) {
                errors.add(path + ": value '" + value + "' does not match pattern '" + pattern + "'");
            }
        }
        if (schema.has("format")) {
            validateStringFormat(path, schema.get("format").asText(), value, errors);
        }
    }

    private static void validateStringFormat(String path, String format, String value, List<String> errors) {
        switch (format) {
            case "uuid" -> {
                if (!UUID_PATTERN.matcher(value).matches()) {
                    errors.add(path + ": invalid uuid format '" + value + "'");
                    return;
                }
                try {
                    UUID.fromString(value);
                } catch (IllegalArgumentException ex) {
                    errors.add(path + ": invalid uuid format '" + value + "'");
                }
            }
            case "date-time" -> {
                try {
                    OffsetDateTime.parse(value);
                } catch (Exception ex) {
                    errors.add(path + ": invalid date-time format '" + value + "'");
                }
            }
            case "date" -> {
                try {
                    LocalDate.parse(value);
                } catch (Exception ex) {
                    errors.add(path + ": invalid date format '" + value + "'");
                }
            }
            case "email" -> {
                if (!EMAIL_PATTERN.matcher(value).matches()) {
                    errors.add(path + ": invalid email format '" + value + "'");
                }
            }
            case "uri" -> {
                try {
                    URI uri = URI.create(value);
                    if (uri.getScheme() == null || uri.getScheme().isBlank()) {
                        errors.add(path + ": invalid uri format '" + value + "'");
                    }
                } catch (IllegalArgumentException ex) {
                    errors.add(path + ": invalid uri format '" + value + "'");
                }
            }
            default -> {
            }
        }
    }

    private static void validateNumericBounds(String path, JsonNode schema, BigDecimal value, List<String> errors) {
        if (schema.has("minimum")) {
            BigDecimal min = schema.get("minimum").decimalValue();
            if (value.compareTo(min) < 0) {
                errors.add(path + ": value " + value + " below minimum " + min);
            }
        }
        if (schema.has("exclusiveMinimum")) {
            BigDecimal min = schema.get("exclusiveMinimum").decimalValue();
            if (value.compareTo(min) <= 0) {
                errors.add(path + ": value " + value + " not above exclusiveMinimum " + min);
            }
        }
        if (schema.has("maximum")) {
            BigDecimal max = schema.get("maximum").decimalValue();
            if (value.compareTo(max) > 0) {
                errors.add(path + ": value " + value + " above maximum " + max);
            }
        }
        if (schema.has("exclusiveMaximum")) {
            BigDecimal max = schema.get("exclusiveMaximum").decimalValue();
            if (value.compareTo(max) >= 0) {
                errors.add(path + ": value " + value + " not below exclusiveMaximum " + max);
            }
        }
    }
}
