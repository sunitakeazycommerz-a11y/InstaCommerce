package com.instacommerce.search.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record SearchRequest(
    @Size(min = 1, max = 256, message = "Query must be between 1 and 256 characters")
    String query,
    String brand,
    String category,
    @Min(value = 0, message = "Minimum price must be non-negative")
    Long minPriceCents,
    @Min(value = 0, message = "Maximum price must be non-negative")
    Long maxPriceCents,
    @Min(value = 0, message = "Page must be non-negative")
    Integer page,
    @Min(value = 1, message = "Size must be at least 1")
    @Max(value = 100, message = "Size must not exceed 100")
    Integer size
) {
    public SearchRequest {
        if (page == null) page = 0;
        if (size == null) size = 20;
    }
}
