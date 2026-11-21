package com.instacommerce.search.service;

import com.instacommerce.platform.web.PageableUtils;
import com.instacommerce.search.dto.AutocompleteResult;
import com.instacommerce.search.dto.FacetValue;
import com.instacommerce.search.dto.SearchResponse;
import com.instacommerce.search.dto.SearchResult;
import com.instacommerce.search.exception.ApiException;
import com.instacommerce.search.repository.SearchDocumentRepository;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SearchService {
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final int MAX_PAGE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int DEFAULT_AUTOCOMPLETE_LIMIT = 10;
    private static final int MAX_PAGE_SIZE = 50;

    private final SearchDocumentRepository searchDocumentRepository;
    private final TrendingService trendingService;
    private final FacetedSearchService facetedSearchService;

    @Autowired
    public SearchService(SearchDocumentRepository searchDocumentRepository,
                         TrendingService trendingService,
                         FacetedSearchService facetedSearchService) {
        this.searchDocumentRepository = searchDocumentRepository;
        this.trendingService = trendingService;
        this.facetedSearchService = facetedSearchService;
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

        Pageable pageable = sanitizePageable(page, size);

        if (normalizedQuery.isEmpty()) {
            return new SearchResponse(List.of(), 0L, pageable.getPageNumber(), 0,
                    Map.of("brand", List.of(), "category", List.of()), null);
        }

        log.info("Executing search query='{}' brand='{}' category='{}' storeId={} page={} size={}",
                normalizedQuery, normalizedBrand, normalizedCategory, storeId,
                pageable.getPageNumber(), pageable.getPageSize());

        Page<Object[]> results = executePrimarySearch(
                normalizedQuery, normalizedBrand, normalizedCategory, minPrice, maxPrice, storeId, pageable);

        List<SearchResult> searchResults = mapSearchResults(results);
        String didYouMean = null;
        long totalResults = results.getTotalElements();
        int totalPages = results.getTotalPages();

        if (searchResults.isEmpty()) {
            boolean hasFilters = normalizedBrand != null || normalizedCategory != null
                    || minPrice != null || maxPrice != null || storeId != null;
            Page<Object[]> fallbackResults = runFuzzyFallbackWithTokenRetry(
                    normalizedQuery, normalizedBrand, normalizedCategory,
                    minPrice, maxPrice, storeId, pageable, hasFilters);

            if (!fallbackResults.isEmpty()) {
                searchResults = mapSearchResults(fallbackResults);
                totalResults = fallbackResults.getTotalElements();
                totalPages = fallbackResults.getTotalPages();
                didYouMean = searchResults.get(0).name();
                log.info("Zero exact matches for '{}'. Fallback found {} items, didYouMean='{}'",
                        normalizedQuery, totalResults, didYouMean);
            }
        }

        boolean hasAttributeFilters = normalizedBrand != null || normalizedCategory != null
                || minPrice != null || maxPrice != null;
        Map<String, List<FacetValue>> facets = hasAttributeFilters
                ? facetedSearchService.computeFilteredFacets(
                        normalizedQuery, normalizedBrand, normalizedCategory, minPrice, maxPrice, storeId)
                : facetedSearchService.computeStoreScopedFacets(normalizedQuery, storeId);

        trendingService.recordQuery(normalizedQuery);

        return new SearchResponse(searchResults, totalResults, pageable.getPageNumber(), totalPages, facets, didYouMean);
    }

    public String buildCacheKey(String query, String brand, String category,
                                Long minPrice, Long maxPrice, UUID storeId, int page, int size) {
        Pageable sanitized = sanitizePageable(page, size);
        return normalizeQuery(query).toLowerCase(Locale.ROOT)
                + "_" + trimToNull(brand)
                + "_" + trimToNull(category)
                + "_" + minPrice
                + "_" + maxPrice
                + "_" + storeId
                + "_" + sanitized.getPageNumber()
                + "_" + sanitized.getPageSize();
    }

    @Cacheable(value = "autocomplete",
               key = "#root.target.buildAutocompleteKey(#prefix, #limit)",
               condition = "#prefix != null && #prefix.trim().length() >= 2")
    public List<AutocompleteResult> autocomplete(String prefix, int limit) {
        String normalized = normalizeQuery(prefix);
        if (normalized.isEmpty()) {
            return List.of();
        }
        int safeLimit = sanitizeLimit(limit);
        log.debug("Autocomplete prefix='{}' limit={}", normalized, safeLimit);
        String safePrefix = escapeLikePattern(normalized);
        return searchDocumentRepository.autocomplete(safePrefix, safeLimit).stream()
                .map(row -> new AutocompleteResult(
                        (String) row[0],
                        (String) row[1],
                        (UUID) row[2]))
                .toList();
    }

    public String buildAutocompleteKey(String prefix, int limit) {
        return normalizeQuery(prefix).toLowerCase(Locale.ROOT) + "_" + sanitizeLimit(limit);
    }

    private Pageable sanitizePageable(int page, int size) {
        int clampedPage = Math.min(Math.max(0, page), MAX_PAGE);
        int boundedSize = Math.max(1, size);
        return PageableUtils.sanitize(PageRequest.of(clampedPage, boundedSize), DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
    }

    private int sanitizeLimit(int limit) {
        return PageableUtils.sanitize(
                PageRequest.of(0, Math.max(1, limit)), DEFAULT_AUTOCOMPLETE_LIMIT, MAX_PAGE_SIZE).getPageSize();
    }

    private Page<Object[]> executePrimarySearch(String query,
                                                String brand,
                                                String category,
                                                Long minPrice,
                                                Long maxPrice,
                                                UUID storeId,
                                                Pageable pageable) {
        return storeId != null
                ? searchDocumentRepository.fullTextSearchScoped(
                        query, brand, category, minPrice, maxPrice, storeId, pageable)
                : searchDocumentRepository.fullTextSearch(
                        query, brand, category, minPrice, maxPrice, pageable);
    }

    private Page<Object[]> runFuzzyFallbackWithTokenRetry(String normalizedQuery,
                                                          String brand,
                                                          String category,
                                                          Long minPrice,
                                                          Long maxPrice,
                                                          UUID storeId,
                                                          Pageable pageable,
                                                          boolean hasFilters) {
        String escaped = escapeLikePattern(normalizedQuery);
        Page<Object[]> fallbackResults = runFuzzyFallback(
                escaped, brand, category, minPrice, maxPrice, storeId, pageable, hasFilters);

        if (fallbackResults.isEmpty() && normalizedQuery.contains(" ")) {
            String fallbackToken = selectLongestSearchToken(normalizedQuery);
            if (fallbackToken != null) {
                fallbackResults = runFuzzyFallback(
                        escapeLikePattern(fallbackToken), brand, category,
                        minPrice, maxPrice, storeId, pageable, hasFilters);
            }
        }
        return fallbackResults;
    }

    private Page<Object[]> runFuzzyFallback(String escapedToken,
                                            String brand,
                                            String category,
                                            Long minPrice,
                                            Long maxPrice,
                                            UUID storeId,
                                            Pageable pageable,
                                            boolean hasFilters) {
        if (!hasFilters) {
            return searchDocumentRepository.fuzzyFallbackSearch(escapedToken, pageable);
        }
        return searchDocumentRepository.fuzzyFallbackSearchFiltered(
                escapedToken, brand, category, minPrice, maxPrice, storeId, pageable);
    }

    private List<SearchResult> mapSearchResults(Page<Object[]> results) {
        return results.getContent().stream()
                .map(this::mapToSearchResult)
                .toList();
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

    private String escapeLikePattern(String input) {
        return input.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private SearchResult mapToSearchResult(Object[] row) {
        return new SearchResult(
                (UUID) row[1],
                (String) row[2],
                (String) row[4],
                (String) row[5],
                ((Number) row[6]).longValue(),
                (String) row[7],
                (Boolean) row[8],
                row[9] != null ? ((Number) row[9]).doubleValue() : 0.0
        );
    }
}
