package com.instacommerce.catalog.dto.response;

import java.util.List;

public record SearchResultResponse(
    List<ProductResponse> results,
    long totalCount,
    int page,
    int size,
    String query,
    long tookMs
) {
}
