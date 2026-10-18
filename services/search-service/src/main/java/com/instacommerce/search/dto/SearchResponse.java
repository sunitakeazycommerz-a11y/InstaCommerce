package com.instacommerce.search.dto;

import java.util.List;
import java.util.Map;

public record SearchResponse(
    List<SearchResult> results,
    long totalResults,
    int page,
    int totalPages,
    Map<String, List<FacetValue>> facets,
    String didYouMean
) {
    public SearchResponse(List<SearchResult> results, long totalResults, int page, int totalPages, Map<String, List<FacetValue>> facets) {
        this(results, totalResults, page, totalPages, facets, null);
    }
}
