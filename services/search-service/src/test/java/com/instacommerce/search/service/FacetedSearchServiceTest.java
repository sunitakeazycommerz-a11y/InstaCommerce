package com.instacommerce.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.search.dto.FacetValue;
import com.instacommerce.search.repository.SearchDocumentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FacetedSearchServiceTest {

    @Mock
    private SearchDocumentRepository searchDocumentRepository;

    private FacetedSearchService facetedSearchService;

    private static final UUID STORE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        facetedSearchService = new FacetedSearchService(searchDocumentRepository);
    }

    @Test
    @DisplayName("computeStoreScopedFacets filters attributes to dark store in-stock variants")
    void computesStoreScopedFacets() {
        List<Object[]> brands = new ArrayList<>();
        brands.add(new Object[]{"Amul", 12L});
        brands.add(new Object[]{"Nestle", 5L});

        List<Object[]> categories = new ArrayList<>();
        categories.add(new Object[]{"Dairy", 15L});
        categories.add(new Object[]{"Snacks", 2L});

        when(searchDocumentRepository.facetByBrandAndStore("milk", STORE_ID)).thenReturn(brands);
        when(searchDocumentRepository.facetByCategoryAndStore("milk", STORE_ID)).thenReturn(categories);

        Map<String, List<FacetValue>> facets = facetedSearchService.computeStoreScopedFacets("milk", STORE_ID);

        assertThat(facets).containsKey("brand");
        assertThat(facets.get("brand")).hasSize(2);
        assertThat(facets.get("brand").get(0).value()).isEqualTo("Amul");
        assertThat(facets.get("brand").get(0).count()).isEqualTo(12L);

        assertThat(facets).containsKey("category");
        assertThat(facets.get("category")).hasSize(2);
        assertThat(facets.get("category").get(0).value()).isEqualTo("Dairy");

        verify(searchDocumentRepository).facetByBrandAndStore("milk", STORE_ID);
        verify(searchDocumentRepository).facetByCategoryAndStore("milk", STORE_ID);
    }

    @Test
    @DisplayName("computeStoreScopedFacets falls back gracefully on empty results without throwing exceptions")
    void handlesEmptyOrNullStoreGracefully() {
        when(searchDocumentRepository.facetByBrand("unknown")).thenReturn(List.of());
        when(searchDocumentRepository.facetByCategory("unknown")).thenReturn(List.of());

        Map<String, List<FacetValue>> facets = facetedSearchService.computeStoreScopedFacets("unknown", null);

        assertThat(facets).containsKey("brand");
        assertThat(facets.get("brand")).isEmpty();
        assertThat(facets).containsKey("category");
        assertThat(facets.get("category")).isEmpty();

        verify(searchDocumentRepository).facetByBrand("unknown");
        verify(searchDocumentRepository).facetByCategory("unknown");
    }
}
