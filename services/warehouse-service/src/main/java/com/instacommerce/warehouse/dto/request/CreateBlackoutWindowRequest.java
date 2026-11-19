package com.instacommerce.warehouse.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateBlackoutWindowRequest(
    @NotNull Instant startsAt,
    @NotNull Instant endsAt,
    @NotBlank @Size(max = 255) String reason
) {
}
