package com.instacommerce.fulfillment.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MarkDeliveredRequest(
    String note
) {
}
