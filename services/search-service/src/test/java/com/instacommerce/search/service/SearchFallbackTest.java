package com.instacommerce.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.search.dto.SearchResponse;
import com.instacommerce.search.repository.SearchDocumentRepository;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class SearchFallbackTest {

    @Mock
    private SearchDocumentRepository searchDocumentRepository;

    @Mock
    private TrendingService trendingService;

    @Mock
    private FacetedSearchService facetedSearchService;

    private SearchService searchService;

    @BeforeEach
    void setUp() {
        searchService = new SearchService(searchDocumentRepository, trendingService, facetedSearchService);
    }

    @Test
    @DisplayName("Zero initial search hits triggers fuzzy fallback and populates didYouMean banner")
    void zeroResultsTriggersFuzzyFallback() {
        String query = "coke zro";

        // Exact full text search returns empty page
        when(searchDocumentRepository.fullTextSearch(
                eq(query), any(), any(), any(), any(), any(PageRequest.class)))
                .thenReturn(Page.empty());

        // Fuzzy fallback returns 1 matching item
        UUID prodId = UUID.randomUUID();
        Object[] fallbackRow = new Object[]{
                UUID.randomUUID(), prodId, "Coca Cola Zero Sugar Can", "Cold carbonated drink",
                "Coca-Cola", "Beverages", 4000L, "http://img.com/coke.png", true, 0.5
        };
        Page<Object[]> fallbackPage = new PageImpl<>(Collections.singletonList(fallbackRow), PageRequest.of(0, 20), 1);

        when(searchDocumentRepository.fuzzyFallbackSearch(eq(query), any(PageRequest.class)))
                .thenReturn(fallbackPage);

        when(facetedSearchService.computeStoreScopedFacets(eq(query), any())).thenReturn(Map.of());

        SearchResponse response = searchService.search(query, null, null, null, null, 0, 20);

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).name()).isEqualTo("Coca Cola Zero Sugar Can");
        assertThat(response.didYouMean()).isEqualTo("Coca Cola Zero Sugar Can");
        assertThat(response.totalResults()).isEqualTo(1);

        verify(searchDocumentRepository).fuzzyFallbackSearch(eq(query), any(PageRequest.class));
        verify(trendingService).recordQuery(query);
    }
}
