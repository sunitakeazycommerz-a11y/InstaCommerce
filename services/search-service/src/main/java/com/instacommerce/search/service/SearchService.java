package com.instacommerce.search.service;

import com.instacommerce.search.dto.AutocompleteResult;
import com.instacommerce.search.dto.SearchResponse;
import com.instacommerce.search.exception.ApiException;
import com.instacommerce.search.repository.SearchDocumentRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SearchService {
    private final TrendingService trendingService;
    private final SearchQueryCacheService searchQueryCacheService;

    public SearchService(SearchDocumentRepository searchDocumentRepository,
                         TrendingService trendingService,
                         FacetedSearchService facetedSearchService) {
        this(trendingService, new SearchQueryCacheService(searchDocumentRepository, facetedSearchService));
    }

    @Autowired
    public SearchService(SearchDocumentRepository searchDocumentRepository,
                         TrendingService trendingService,
                         FacetedSearchService facetedSearchService,
                         ObjectProvider<SearchQueryCacheService> searchQueryCacheServiceProvider) {
        this(trendingService,
                searchQueryCacheServiceProvider != null
                        ? searchQueryCacheServiceProvider.getIfAvailable(
                                () -> new SearchQueryCacheService(searchDocumentRepository, facetedSearchService))
                        : new SearchQueryCacheService(searchDocumentRepository, facetedSearchService));
    }

    SearchService(TrendingService trendingService, SearchQueryCacheService searchQueryCacheService) {
        this.trendingService = trendingService;
        this.searchQueryCacheService = searchQueryCacheService;
    }

    public SearchResponse search(String query, String brand, String category,
                                 Long minPrice, Long maxPrice, int page, int size) {
        return search(query, brand, category, minPrice, maxPrice, null, page, size);
    }

    public SearchResponse search(String query, String brand, String category,
                                 Long minPrice, Long maxPrice, UUID storeId, int page, int size) {
        String normalizedQuery = SearchQueryCacheService.normalizeQuery(query);
        String normalizedBrand = SearchQueryCacheService.trimToNull(brand);
        String normalizedCategory = SearchQueryCacheService.trimToNull(category);
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PRICE_RANGE",
                    "minPriceCents cannot exceed maxPriceCents");
        }

        SearchResponse response = searchQueryCacheService.executeSearch(
                normalizedQuery, normalizedBrand, normalizedCategory, minPrice, maxPrice, storeId, page, size);

        if (!normalizedQuery.isEmpty()) {
            trendingService.recordQuery(normalizedQuery);
        }
        return response;
    }

    public String buildCacheKey(String query, String brand, String category,
                                Long minPrice, Long maxPrice, UUID storeId, int page, int size) {
        return searchQueryCacheService.buildCacheKey(query, brand, category, minPrice, maxPrice, storeId, page, size);
    }

    public List<AutocompleteResult> autocomplete(String prefix, int limit) {
        return searchQueryCacheService.autocomplete(prefix, limit);
    }

    public String buildAutocompleteKey(String prefix, int limit) {
        return searchQueryCacheService.buildAutocompleteKey(prefix, limit);
    }
}
