package com.instacommerce.pricing.consumer;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogProductEvent(
    @JsonAlias({"productId", "product_id"}) UUID id,
    String sku,
    String name,
    boolean active
) {
}
