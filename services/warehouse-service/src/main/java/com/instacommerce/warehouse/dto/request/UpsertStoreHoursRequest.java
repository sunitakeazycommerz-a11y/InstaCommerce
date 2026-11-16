package com.instacommerce.warehouse.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpsertStoreHoursRequest(
    @NotNull @Min(0) @Max(6) Integer dayOfWeek,
    @NotNull LocalTime opensAt,
    @NotNull LocalTime closesAt,
    Boolean holiday
) {
}
