package com.instacommerce.notification.service;

public final class MaskingUtil {
    private MaskingUtil() {
    }

    public static String maskRecipient(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return "";
        }
        if (recipient.contains("@")) {
            return maskEmail(recipient);
        }
        return maskPhone(recipient);
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
