package com.instacommerce.search.service;

import com.instacommerce.search.dto.AutocompleteResult;
import com.instacommerce.search.dto.FacetValue;
import com.instacommerce.search.dto.SearchResponse;
import com.instacommerce.search.dto.SearchResult;
import com.instacommerce.search.exception.ApiException;
import com.instacommerce.search.repository.SearchDocumentRepository;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SearchService {
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final SearchDocumentRepository searchDocumentRepository;
    private final TrendingService trendingService;
    private final FacetedSearchService facetedSearchService;

    public SearchService(SearchDocumentRepository searchDocumentRepository,
                         TrendingService trendingService,
                         FacetedSearchService facetedSearchService) {
        this.searchDocumentRepository = searchDocumentRepository;
        this.trendingService = trendingService;
        this.facetedSearchService = facetedSearchService;
    }

    public SearchService(SearchDocumentRepository searchDocumentRepository, TrendingService trendingService) {
        this(searchDocumentRepository, trendingService, new FacetedSearchService(searchDocumentRepository));
    }

    public SearchResponse search(String query, String brand, String category,
                                 Long minPrice, Long maxPrice, int page, int size) {
        return search(query, brand, category, minPrice, maxPrice, null, page, size);
    }

    @Cacheable(value = "searchResults",
               key = "#root.target.buildCacheKey(#query, #brand, #category, #minPrice, #maxPrice, #storeId, #page, #size)",
               condition = "#query != null && !#query.isBlank()")
    public SearchResponse search(String query, String brand, String category,
                                 Long minPrice, Long maxPrice, UUID storeId, int page, int size) {
        String normalizedQuery = normalizeQuery(query);
        String normalizedBrand = trimToNull(brand);
        String normalizedCategory = trimToNull(category);
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PRICE_RANGE",
                    "minPriceCents cannot exceed maxPriceCents");
        }

        int safePage = Math.min(Math.max(0, page), 50);
        int safeSize = Math.min(Math.max(1, size), 50);

        if (normalizedQuery.isEmpty()) {
            return new SearchResponse(List.of(), 0L, safePage, 0,
                    Map.of("brand", List.of(), "category", List.of()), null);
        }

        log.info("Executing search query='{}' brand='{}' category='{}' storeId={} page={} size={}",
                normalizedQuery, normalizedBrand, normalizedCategory, storeId, safePage, safeSize);

        PageRequest pageable = PageRequest.of(safePage, safeSize);
        Page<Object[]> results = storeId != null
                ? searchDocumentRepository.fullTextSearchScoped(
                        normalizedQuery, normalizedBrand, normalizedCategory, minPrice, maxPrice, storeId, pageable)
                : searchDocumentRepository.fullTextSearch(
                        normalizedQuery, normalizedBrand, normalizedCategory, minPrice, maxPrice, pageable);

        List<SearchResult> searchResults = results.getContent().stream()
                .map(this::mapToSearchResult)
                .collect(Collectors.toList());

        String didYouMean = null;
        long totalResults = results.getTotalElements();
        int totalPages = results.getTotalPages();

        if (searchResults.isEmpty()) {
            boolean hasFilters = normalizedBrand != null || normalizedCategory != null
                    || minPrice != null || maxPrice != null || storeId != null;
            String escaped = escapeLikePattern(normalizedQuery);
            Page<Object[]> fallbackResults = runFuzzyFallback(
                    escaped, normalizedBrand, normalizedCategory, minPrice, maxPrice, storeId, pageable, hasFilters);

            if (fallbackResults.isEmpty() && normalizedQuery.contains(" ")) {
                String fallbackToken = selectLongestSearchToken(normalizedQuery);
                if (fallbackToken != null) {
                    fallbackResults = runFuzzyFallback(
                            escapeLikePattern(fallbackToken), normalizedBrand, normalizedCategory,
                            minPrice, maxPrice, storeId, pageable, hasFilters);
                }
            }

            if (!fallbackResults.isEmpty()) {
                searchResults = fallbackResults.getContent().stream()
                        .map(this::mapToSearchResult)
                        .collect(Collectors.toList());
                totalResults = fallbackResults.getTotalElements();
                totalPages = fallbackResults.getTotalPages();
                didYouMean = searchResults.get(0).name();
                log.info("Zero exact matches for '{}'. Fallback found {} items, didYouMean='{}'",
                        normalizedQuery, totalResults, didYouMean);
            }
        }

        Map<String, List<FacetValue>> facets;
        if (facetedSearchService != null) {
            boolean hasAttributeFilters = normalizedBrand != null || normalizedCategory != null
                    || minPrice != null || maxPrice != null;
            facets = hasAttributeFilters
                    ? facetedSearchService.computeFilteredFacets(
                            normalizedQuery, normalizedBrand, normalizedCategory, minPrice, maxPrice, storeId)
                    : facetedSearchService.computeStoreScopedFacets(normalizedQuery, storeId);
        } else {
            facets = buildFacets(normalizedQuery);
        }

        trendingService.recordQuery(normalizedQuery);

        return new SearchResponse(searchResults, totalResults, safePage, totalPages, facets, didYouMean);
    }

    public String buildCacheKey(String query, String brand, String category,
                                Long minPrice, Long maxPrice, UUID storeId, int page, int size) {
        return normalizeQuery(query).toLowerCase(Locale.ROOT)
                + "_" + trimToNull(brand)
                + "_" + trimToNull(category)
                + "_" + minPrice
                + "_" + maxPrice
                + "_" + storeId
                + "_" + Math.min(Math.max(0, page), 50)
                + "_" + Math.min(Math.max(1, size), 50);
    }

    @Cacheable(value = "autocomplete",
               key = "#root.target.buildAutocompleteKey(#prefix, #limit)",
               condition = "#prefix != null && #prefix.trim().length() >= 2")
    public List<AutocompleteResult> autocomplete(String prefix, int limit) {
        String normalized = normalizeQuery(prefix);
        if (normalized.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.min(Math.max(1, limit), 50);
        log.debug("Autocomplete prefix='{}' limit={}", normalized, safeLimit);
        String safePrefix = escapeLikePattern(normalized);
        return searchDocumentRepository.autocomplete(safePrefix, safeLimit).stream()
                .map(row -> new AutocompleteResult(
                        (String) row[0],
                        (String) row[1],
                        (UUID) row[2]))
                .collect(Collectors.toList());
    }

    public String buildAutocompleteKey(String prefix, int limit) {
        return normalizeQuery(prefix).toLowerCase(Locale.ROOT) + "_" + Math.min(Math.max(1, limit), 50);
    }

    private Page<Object[]> runFuzzyFallback(String escapedToken,
                                            String brand,
                                            String category,
                                            Long minPrice,
                                            Long maxPrice,
                                            UUID storeId,
                                            PageRequest pageable,
                                            boolean hasFilters) {
        if (!hasFilters) {
            return searchDocumentRepository.fuzzyFallbackSearch(escapedToken, pageable);
        }
        return searchDocumentRepository.fuzzyFallbackSearchFiltered(
                escapedToken, brand, category, minPrice, maxPrice, storeId, pageable);
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        return query.trim().replaceAll("\\s+", " ");
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String selectLongestSearchToken(String normalizedQuery) {
        return Arrays.stream(normalizedQuery.split(" "))
                .filter(token -> token.length() >= 3)
                .max(Comparator.comparingInt(String::length))
                .orElse(null);
    }

    private Map<String, List<FacetValue>> buildFacets(String query) {
        Map<String, List<FacetValue>> facets = new HashMap<>();
        try {
            List<FacetValue> brandFacets = searchDocumentRepository.facetByBrand(query).stream()
                    .map(row -> new FacetValue((String) row[0], ((Number) row[1]).longValue()))
                    .collect(Collectors.toList());
            facets.put("brand", brandFacets);

            List<FacetValue> categoryFacets = searchDocumentRepository.facetByCategory(query).stream()
                    .map(row -> new FacetValue((String) row[0], ((Number) row[1]).longValue()))
                    .collect(Collectors.toList());
            facets.put("category", categoryFacets);
        } catch (Exception ex) {
            log.warn("Failed to compute facets for query='{}'", query, ex);
        }
        return facets;
    }

    private String escapeLikePattern(String input) {
        return input.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private SearchResult mapToSearchResult(Object[] row) {
        return new SearchResult(
                (UUID) row[1],       // product_id
                (String) row[2],     // name
                (String) row[4],     // brand
                (String) row[5],     // category
                ((Number) row[6]).longValue(), // price_cents
                (String) row[7],     // image_url
                (Boolean) row[8],    // in_stock
                row[9] != null ? ((Number) row[9]).doubleValue() : 0.0 // rank
        );
    }
}
