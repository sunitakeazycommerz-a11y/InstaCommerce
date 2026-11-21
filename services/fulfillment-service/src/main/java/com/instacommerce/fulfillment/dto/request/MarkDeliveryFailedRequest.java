package com.instacommerce.fulfillment.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MarkDeliveryFailedRequest(
    @NotBlank String reason,
    String failureCode,
    String notes,
    UUID riderId
) {
    public MarkDeliveryFailedRequest(String reason) {
        this(reason, null, null, null);
    }

    public MarkDeliveryFailedRequest(String reason, String failureCode, String notes) {
        this(reason, failureCode, notes, null);
    }
}
