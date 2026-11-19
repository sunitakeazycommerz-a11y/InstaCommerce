package com.instacommerce.catalog.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateCategoryRequest(
    @NotBlank @Size(max = 100) String name,
    @Size(max = 120) String slug,
    UUID parentId,
    Integer sortOrder,
    Boolean active
) {
}
