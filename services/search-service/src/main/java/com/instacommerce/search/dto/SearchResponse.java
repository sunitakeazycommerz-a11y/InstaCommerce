package com.instacommerce.search.dto;

import java.util.List;
import java.util.Map;

public record SearchResponse(
    List<SearchResult> results,
    long totalResults,
    int page,
    int totalPages,
    Map<String, List<FacetValue>> facets
) {
}
