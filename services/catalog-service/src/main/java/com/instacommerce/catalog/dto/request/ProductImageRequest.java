package com.instacommerce.catalog.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductImageRequest(
    @NotBlank String url,
    String altText,
    Boolean isPrimary
) {
}
