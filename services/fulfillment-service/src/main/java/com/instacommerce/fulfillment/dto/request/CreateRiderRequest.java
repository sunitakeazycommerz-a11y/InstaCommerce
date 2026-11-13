package com.instacommerce.fulfillment.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateRiderRequest(
    @NotBlank String name,
    @Pattern(regexp = "^\\+?[1-9]\\d{6,14}$") String phone,
    @NotNull UUID storeId
) {
}
