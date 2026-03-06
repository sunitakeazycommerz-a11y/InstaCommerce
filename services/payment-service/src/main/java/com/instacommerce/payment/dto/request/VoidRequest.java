package com.instacommerce.payment.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VoidRequest(
    String idempotencyKey
) {
}
