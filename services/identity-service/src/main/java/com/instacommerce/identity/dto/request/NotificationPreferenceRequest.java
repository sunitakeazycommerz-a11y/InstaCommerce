package com.instacommerce.identity.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationPreferenceRequest(
    boolean emailOptOut,
    boolean smsOptOut,
    boolean pushOptOut,
    boolean marketingOptOut
) {
}
