package com.instacommerce.fulfillment.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateRiderRequest(
    @NotBlank String name,
    @Pattern(regexp = "^\\+?[1-9]\\d{6,14}$") String phone,
    @NotBlank String storeId
) {
}
