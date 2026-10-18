package com.instacommerce.warehouse.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OptimizePathRequest(
    String startLocation,
    String endLocation,
    @NotEmpty List<@Valid PickItemLocationRequest> items
) {
}
