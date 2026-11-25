package com.instacommerce.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.search.config.KafkaConfig;
import com.instacommerce.search.domain.model.SearchDocument;
import com.instacommerce.search.dto.SearchResponse;
import com.instacommerce.search.repository.SearchDocumentRepository;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class SearchEdgeFixesTest {

    @Test
    @DisplayName("ProductUpdated does not overwrite existing inStock flag while initializing inStock on new insert")
    void upsertDocumentPreservesExistingInStock() {
        SearchDocumentRepository repository = mock(SearchDocumentRepository.class);
        SearchIndexService indexService = new SearchIndexService(repository);

        UUID productId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        SearchDocument existing = new SearchDocument(
                productId, "Old Name", "Old Desc", "Brand", "Dairy", 5000L, null, true, storeId);
        when(repository.findByProductId(productId)).thenReturn(Optional.of(existing));

        indexService.upsertDocument(
                productId, "New Name", "New Desc", "Brand", "Dairy", 5500L, "https://img/new.png", false, storeId);

        assertThat(existing.getName()).isEqualTo("New Name");
        assertThat(existing.getPriceCents()).isEqualTo(5500L);
        assertThat(existing.isInStock()).isTrue();
        verify(repository).save(existing);

        UUID newProductId = UUID.randomUUID();
        when(repository.findByProductId(newProductId)).thenReturn(Optional.empty());
        indexService.upsertDocument(
                newProductId, "Fresh Mango", "Sweet", "Farm", "Fruits", 9000L, null, true, storeId);

        verify(repository).save(org.mockito.ArgumentMatchers.argThat(
                doc -> doc.getProductId().equals(newProductId) && doc.isInStock()));
    }

    @Test
    @DisplayName("SearchService records trending queries even on cached search hits")
    void cachedSearchStillRecordsTrendingQuery() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(CachingTestConfig.class)) {
            SearchDocumentRepository repo = ctx.getBean(SearchDocumentRepository.class);
            TrendingService trendingService = ctx.getBean(TrendingService.class);
            SearchService searchService = ctx.getBean(SearchService.class);

            Object[] row = new Object[]{
                    UUID.randomUUID(), UUID.randomUUID(), "Whole Milk", "Fresh", "Amul", "Dairy", 6000L, null, true, 0.9
            };
            Page<Object[]> page = new PageImpl<>(Collections.singletonList(row), PageRequest.of(0, 20), 1);
            when(repo.fullTextSearch(eq("milk"), any(), any(), any(), any(), any())).thenReturn(page);
            when(repo.facetByBrand("milk")).thenReturn(List.of());
            when(repo.facetByCategory("milk")).thenReturn(List.of());

            SearchResponse first = searchService.search("milk", null, null, null, null, null, 0, 20);
            SearchResponse second = searchService.search("milk", null, null, null, null, null, 0, 20);

            assertThat(first.results()).hasSize(1);
            assertThat(second.results()).hasSize(1);
            verify(repo, times(1)).fullTextSearch(eq("milk"), any(), any(), any(), any(), any());
            verify(trendingService, times(2)).recordQuery("milk");
        }
    }

    @Test
    @DisplayName("Search KafkaConfig serializes byte[], String, and Map DLT records cleanly")
    void searchKafkaConfigSerializesDltTypes() {
        KafkaConfig kafkaConfig = new KafkaConfig();
        ReflectionTestUtils.setField(kafkaConfig, "bootstrapServers", "localhost:9092");

        DefaultKafkaProducerFactory<String, Object> pf =
                (DefaultKafkaProducerFactory<String, Object>) kafkaConfig.producerFactory();
        Serializer<Object> valueSerializer = pf.getValueSerializer();
        assertThat(valueSerializer).isNotNull();

        byte[] raw = "{malformed".getBytes(StandardCharsets.UTF_8);
        assertThat(valueSerializer.serialize("catalog.events.DLT", raw)).isEqualTo(raw);
        assertThat(valueSerializer.serialize("catalog.events.DLT", "raw-string"))
                .isEqualTo("raw-string".getBytes(StandardCharsets.UTF_8));
        assertThat(valueSerializer.serialize("catalog.events.DLT", Map.of("eventType", "ProductUpdated")))
                .asString(StandardCharsets.UTF_8)
                .contains("ProductUpdated");
        assertThat(kafkaConfig.kafkaErrorHandler(new KafkaTemplate<>(pf))).isNotNull();
    }

    @Configuration
    @EnableCaching
    static class CachingTestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("searchResults", "autocomplete");
        }

        @Bean
        SearchDocumentRepository searchDocumentRepository() {
            return mock(SearchDocumentRepository.class);
        }

        @Bean
        TrendingService trendingService() {
            return mock(TrendingService.class);
        }

        @Bean
        FacetedSearchService facetedSearchService(SearchDocumentRepository repo) {
            return new FacetedSearchService(repo, null);
        }

        @Bean
        SearchQueryCacheService searchQueryCacheService(SearchDocumentRepository repo, FacetedSearchService facets) {
            return new SearchQueryCacheService(repo, facets);
        }

        @Bean
        SearchService searchService(SearchDocumentRepository repo,
                                    TrendingService trendingService,
                                    FacetedSearchService facets,
                                    org.springframework.beans.factory.ObjectProvider<SearchQueryCacheService> cacheProvider) {
            return new SearchService(repo, trendingService, facets, cacheProvider);
        }
    }
}
