package com.instacommerce.notification.domain.valueobject;

public record TemplateId(String value) {
    public TemplateId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TemplateId cannot be blank");
        }
    }
}
