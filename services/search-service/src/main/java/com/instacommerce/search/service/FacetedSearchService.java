package com.instacommerce.search.service;

import com.instacommerce.search.dto.FacetValue;
import com.instacommerce.search.repository.SearchDocumentRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dark store aware faceted search aggregation service.
 *
 * Scopes brand and category attribute facets to the customer's active dark store
 * and rolls up in-stock variants, preventing customers from selecting facet filters
 * that yield zero available items.
 */
@Service
public class FacetedSearchService {

    private static final Logger log = LoggerFactory.getLogger(FacetedSearchService.class);

    private final SearchDocumentRepository searchDocumentRepository;

    public FacetedSearchService(SearchDocumentRepository searchDocumentRepository) {
        this.searchDocumentRepository = searchDocumentRepository;
    }

    public record FacetRollupResult(
            Map<String, List<FacetValue>> facets,
            long totalInStockVariants,
            UUID storeId
    ) {}

    /**
     * Computes facet distribution scoped to the customer's active dark store ID.
     *
     * @param query Search query string
     * @param storeId Customer's local dark store UUID (optional)
     * @return Map of facet category to list of facet values with available stock counts
     */
    @Transactional(readOnly = true)
    public Map<String, List<FacetValue>> computeStoreScopedFacets(String query, UUID storeId) {
        Map<String, List<FacetValue>> facets = new HashMap<>();
        try {
            List<Object[]> rawBrands = (storeId != null)
                    ? searchDocumentRepository.facetByBrandAndStore(query, storeId)
                    : searchDocumentRepository.facetByBrand(query);

            List<FacetValue> brandFacets = rawBrands.stream()
                    .filter(row -> row[0] != null && !((String) row[0]).isBlank())
                    .map(row -> new FacetValue((String) row[0], ((Number) row[1]).longValue()))
                    .collect(Collectors.toList());
            facets.put("brand", brandFacets);

            List<Object[]> rawCategories = (storeId != null)
                    ? searchDocumentRepository.facetByCategoryAndStore(query, storeId)
                    : searchDocumentRepository.facetByCategory(query);

            List<FacetValue> categoryFacets = rawCategories.stream()
                    .filter(row -> row[0] != null && !((String) row[0]).isBlank())
                    .map(row -> new FacetValue((String) row[0], ((Number) row[1]).longValue()))
                    .collect(Collectors.toList());
            facets.put("category", categoryFacets);

            log.debug("Computed facets for query='{}' storeId={}: brands={}, categories={}",
                    query, storeId, brandFacets.size(), categoryFacets.size());
        } catch (Exception ex) {
            log.warn("Failed to compute store-scoped facets for query='{}' storeId={}", query, storeId, ex);
            facets.put("brand", new ArrayList<>());
            facets.put("category", new ArrayList<>());
        }
        return facets;
    }
}
