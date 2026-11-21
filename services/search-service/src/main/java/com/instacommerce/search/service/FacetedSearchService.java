package com.instacommerce.search.service;

import com.instacommerce.search.dto.FacetValue;
import com.instacommerce.search.repository.SearchDocumentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FacetedSearchService {

    private static final Logger log = LoggerFactory.getLogger(FacetedSearchService.class);

    private final SearchDocumentRepository searchDocumentRepository;
    private final Counter facetFailureCounter;

    @Autowired
    public FacetedSearchService(SearchDocumentRepository searchDocumentRepository,
                                ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.searchDocumentRepository = searchDocumentRepository;
        MeterRegistry registry = meterRegistryProvider != null ? meterRegistryProvider.getIfAvailable() : null;
        if (registry == null) {
            registry = new SimpleMeterRegistry();
        }
        this.facetFailureCounter = Counter.builder("search.facets.compute.failures")
                .register(registry);
    }

    public record FacetRollupResult(
            Map<String, List<FacetValue>> facets,
            long totalInStockVariants,
            UUID storeId
    ) {}

    @Transactional(readOnly = true)
    public Map<String, List<FacetValue>> computeStoreScopedFacets(String query, UUID storeId) {
        return computeFilteredFacets(query, null, null, null, null, storeId);
    }

    @Transactional(readOnly = true)
    public Map<String, List<FacetValue>> computeFilteredFacets(String query,
                                                               String brand,
                                                               String category,
                                                               Long minPrice,
                                                               Long maxPrice,
                                                               UUID storeId) {
        Map<String, List<FacetValue>> facets = new HashMap<>();
        boolean hasAttributeFilters = brand != null || category != null || minPrice != null || maxPrice != null;
        try {
            List<Object[]> rawBrands;
            List<Object[]> rawCategories;
            if (hasAttributeFilters) {
                rawBrands = searchDocumentRepository.facetByBrandFiltered(
                        query, brand, category, minPrice, maxPrice, storeId);
                rawCategories = searchDocumentRepository.facetByCategoryFiltered(
                        query, brand, category, minPrice, maxPrice, storeId);
            } else if (storeId != null) {
                rawBrands = searchDocumentRepository.facetByBrandAndStore(query, storeId);
                rawCategories = searchDocumentRepository.facetByCategoryAndStore(query, storeId);
            } else {
                rawBrands = searchDocumentRepository.facetByBrand(query);
                rawCategories = searchDocumentRepository.facetByCategory(query);
            }

            List<FacetValue> brandFacets = mapFacetRows(rawBrands);
            facets.put("brand", brandFacets);

            List<FacetValue> categoryFacets = mapFacetRows(rawCategories);
            facets.put("category", categoryFacets);

            log.debug("Computed facets for query='{}' storeId={}: brands={}, categories={}",
                    query, storeId, brandFacets.size(), categoryFacets.size());
        } catch (Exception ex) {
            facetFailureCounter.increment();
            log.warn("Failed to compute store-scoped facets for query='{}' storeId={}", query, storeId, ex);
            facets.put("brand", new ArrayList<>());
            facets.put("category", new ArrayList<>());
        }
        return facets;
    }

    private List<FacetValue> mapFacetRows(List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<>();
        }
        return rows.stream()
                .filter(row -> row[0] != null && !((String) row[0]).isBlank())
                .map(row -> new FacetValue((String) row[0], ((Number) row[1]).longValue()))
                .toList();
    }
}
