package com.instacommerce.notification.service;

import com.instacommerce.notification.domain.model.NotificationChannel;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class MaskingUtil {
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9\\s().-]{7,20}$");
    private static final Set<String> PII_VARIABLE_KEYS = Set.of(
        "phone",
        "email",
        "recipient",
        "devicetoken",
        "pushtoken",
        "token"
    );

    private MaskingUtil() {
    }

    public static Map<String, Object> stripPiiVariables(Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String key = entry.getKey();
            if (key == null || PII_VARIABLE_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            sanitized.put(key, entry.getValue());
        }
        return sanitized;
    }

    public static String maskRecipient(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return "";
        }
        String trimmed = recipient.trim();
        if (trimmed.contains("@")) {
            return maskEmail(trimmed);
        }
        if (PHONE_PATTERN.matcher(trimmed).matches()) {
            return maskPhone(trimmed);
        }
        return maskToken(trimmed);
    }

    public static String maskRecipient(String recipient, NotificationChannel channel) {
        if (recipient == null || recipient.isBlank()) {
            return "";
        }
        if (channel == null) {
            return maskRecipient(recipient);
        }
        String trimmed = recipient.trim();
        return switch (channel) {
            case EMAIL -> trimmed.contains("@") ? maskEmail(trimmed) : maskToken(trimmed);
            case SMS -> maskPhone(trimmed);
            case PUSH -> maskToken(trimmed);
        };
    }

    public static String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        String trimmed = token.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 4) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    private static String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) {
            return "***" + email.substring(atIndex);
        }
        String domain = email.substring(atIndex);
        return email.substring(0, 1) + "***" + domain;
    }

    private static String maskPhone(String phone) {
        String digits = phone.replaceAll("[^0-9+]", "");
        if (digits.length() <= 4) {
            return "****";
        }
        String suffix = digits.substring(digits.length() - 4);
        return digits.substring(0, Math.min(3, digits.length() - 4)) + "****" + suffix;
    }
}
