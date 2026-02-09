package com.instacommerce.search.dto;

import java.util.UUID;

public record SearchResult(
    UUID productId,
    String name,
    String brand,
    String category,
    long priceCents,
    String imageUrl,
    boolean inStock,
    double score
) {
}
