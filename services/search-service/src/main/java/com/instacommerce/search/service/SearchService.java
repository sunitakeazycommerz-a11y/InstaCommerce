package com.instacommerce.search.service;

import com.instacommerce.search.dto.AutocompleteResult;
import com.instacommerce.search.dto.FacetValue;
import com.instacommerce.search.dto.SearchResponse;
import com.instacommerce.search.dto.SearchResult;
import com.instacommerce.search.repository.SearchDocumentRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class SearchService {
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final SearchDocumentRepository searchDocumentRepository;
    private final TrendingService trendingService;

    public SearchService(SearchDocumentRepository searchDocumentRepository, TrendingService trendingService) {
        this.searchDocumentRepository = searchDocumentRepository;
        this.trendingService = trendingService;
    }

    @Cacheable(value = "searchResults",
               key = "#query + '_' + #brand + '_' + #category + '_' + #minPrice + '_' + #maxPrice + '_' + #page + '_' + #size",
               condition = "#query != null && !#query.isBlank()")
    public SearchResponse search(String query, String brand, String category,
                                 Long minPrice, Long maxPrice, int page, int size) {
        log.info("Executing search query='{}' brand='{}' category='{}' page={} size={}", query, brand, category, page, size);

        Page<Object[]> results = searchDocumentRepository.fullTextSearch(
                query, brand, category, minPrice, maxPrice, PageRequest.of(page, size));

        List<SearchResult> searchResults = results.getContent().stream()
                .map(this::mapToSearchResult)
                .collect(Collectors.toList());

        Map<String, List<FacetValue>> facets = buildFacets(query);

        // Record the query for trending asynchronously
        trendingService.recordQuery(query);

        return new SearchResponse(searchResults, results.getTotalElements(), page, results.getTotalPages(), facets);
    }

    @Cacheable(value = "autocomplete", key = "#prefix + '_' + #limit",
               condition = "#prefix != null && #prefix.length() >= 2")
    public List<AutocompleteResult> autocomplete(String prefix, int limit) {
        log.debug("Autocomplete prefix='{}' limit={}", prefix, limit);
        String safePrefix = escapeLikePattern(prefix);
        return searchDocumentRepository.autocomplete(safePrefix, limit).stream()
                .map(row -> new AutocompleteResult(
                        (String) row[0],
                        (String) row[1],
                        (UUID) row[2]))
                .collect(Collectors.toList());
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
                row[11] != null ? ((Number) row[11]).doubleValue() : 0.0 // rank
        );
    }
}
