package com.instacommerce.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.search.dto.FacetValue;
import com.instacommerce.search.dto.SearchResponse;
import com.instacommerce.search.exception.ApiException;
import com.instacommerce.search.repository.SearchDocumentRepository;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

class SearchNormalizationAndFilteringTest {

    @Test
    @DisplayName("SearchService normalizes query whitespace and rejects inverted price bounds")
    void normalizesQueryAndRejectsInvertedPriceBounds() {
        SearchDocumentRepository repo = mock(SearchDocumentRepository.class);
        TrendingService trending = mock(TrendingService.class);
        FacetedSearchService facets = new FacetedSearchService(repo, null);
        SearchService service = new SearchService(repo, trending, facets);

        when(repo.fullTextSearch(eq("whole milk"), eq("Amul"), eq("Dairy"), eq(1000L), eq(5000L), any()))
                .thenReturn(Page.empty());
        when(repo.fuzzyFallbackSearchFiltered(
                eq("whole milk"), eq("Amul"), eq("Dairy"), eq(1000L), eq(5000L), eq(null), any()))
                .thenReturn(Page.empty());
        when(repo.fuzzyFallbackSearchFiltered(
                eq("whole"), eq("Amul"), eq("Dairy"), eq(1000L), eq(5000L), eq(null), any()))
                .thenReturn(Page.empty());
        when(repo.facetByBrandFiltered(eq("whole milk"), eq("Amul"), eq("Dairy"), eq(1000L), eq(5000L), eq(null)))
                .thenReturn(List.of());
        when(repo.facetByCategoryFiltered(eq("whole milk"), eq("Amul"), eq("Dairy"), eq(1000L), eq(5000L), eq(null)))
                .thenReturn(List.of());

        SearchResponse empty = service.search("   ", null, null, null, null, null, 0, 20);
        assertThat(empty.results()).isEmpty();
        verify(repo, never()).fullTextSearch(any(), any(), any(), any(), any(), any());

        service.search("  whole   milk  ", " Amul ", " Dairy ", 1000L, 5000L, null, 0, 20);
        verify(trending).recordQuery("whole milk");
        verify(repo).facetByBrandFiltered("whole milk", "Amul", "Dairy", 1000L, 5000L, null);

        assertThatThrownBy(() -> service.search("milk", null, null, 5000L, 1000L, null, 0, 20))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("INVALID_PRICE_RANGE");
    }

    @Test
    @DisplayName("Store-scoped search routes through storeId full-text, filtered fuzzy fallback, and store-scoped facets")
    void storeScopedSearchPropagatesStoreIdAndActiveFilters() {
        SearchDocumentRepository repo = mock(SearchDocumentRepository.class);
        TrendingService trending = mock(TrendingService.class);
        FacetedSearchService facets = new FacetedSearchService(repo, null);
        SearchService service = new SearchService(repo, trending, facets);

        UUID storeId = UUID.randomUUID();
        UUID prodId = UUID.randomUUID();
        Object[] row = new Object[]{
                UUID.randomUUID(), prodId, "Organic Banana", "Fresh", "Farm", "Fruits", 4500L, null, true, 0.5
        };
        Page<Object[]> fallbackPage = new PageImpl<>(Collections.singletonList(row), PageRequest.of(0, 20), 1);

        when(repo.fullTextSearchScoped(eq("organic banan"), eq(null), eq(null), eq(null), eq(null), eq(storeId), any()))
                .thenReturn(Page.empty());
        when(repo.fuzzyFallbackSearchFiltered(
                eq("organic banan"), eq(null), eq(null), eq(null), eq(null), eq(storeId), any()))
                .thenReturn(Page.empty());
        when(repo.fuzzyFallbackSearchFiltered(
                eq("organic"), eq(null), eq(null), eq(null), eq(null), eq(storeId), any()))
                .thenReturn(fallbackPage);
        when(repo.facetByBrandAndStore("organic banan", storeId))
                .thenReturn(Collections.singletonList(new Object[]{"Farm", 1L}));
        when(repo.facetByCategoryAndStore("organic banan", storeId))
                .thenReturn(Collections.singletonList(new Object[]{"Fruits", 1L}));

        SearchResponse response = service.search("organic banan", null, null, null, null, storeId, 0, 20);

        assertThat(response.results()).hasSize(1);
        assertThat(response.didYouMean()).isEqualTo("Organic Banana");
        Map<String, List<FacetValue>> facetMap = response.facets();
        assertThat(facetMap.get("brand")).extracting(FacetValue::value).containsExactly("Farm");
    }

    @Test
    @DisplayName("SearchService clamps page, size, and autocomplete limit consistently via PageableUtils")
    void clampsPageableAndAutocompleteBounds() {
        SearchDocumentRepository repo = mock(SearchDocumentRepository.class);
        TrendingService trending = mock(TrendingService.class);
        FacetedSearchService facets = new FacetedSearchService(repo, null);
        SearchService service = new SearchService(repo, trending, facets);

        when(repo.fullTextSearch(eq("milk"), eq(null), eq(null), eq(null), eq(null), any()))
                .thenReturn(Page.empty());
        when(repo.fuzzyFallbackSearch(eq("milk"), any())).thenReturn(Page.empty());
        when(repo.autocomplete(eq("mi"), eq(50))).thenReturn(List.of());

        service.search("milk", null, null, null, null, null, 99, 250);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repo).fullTextSearch(eq("milk"), eq(null), eq(null), eq(null), eq(null), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(50);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(50);
        assertThat(service.buildCacheKey("milk", null, null, null, null, null, 99, 250))
                .endsWith("_50_50");

        service.autocomplete("mi", 200);
        verify(repo).autocomplete("mi", 50);
        assertThat(service.buildAutocompleteKey("mi", 200)).isEqualTo("mi_50");
    }
}
