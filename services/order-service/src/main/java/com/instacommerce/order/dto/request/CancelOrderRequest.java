package com.instacommerce.order.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CancelOrderRequest(
    @NotBlank @Size(max = 200) String reason
) {
}
