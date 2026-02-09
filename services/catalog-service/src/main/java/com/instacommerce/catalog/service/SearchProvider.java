package com.instacommerce.catalog.service;

import com.instacommerce.catalog.dto.response.SearchResultResponse;
import org.springframework.data.domain.Pageable;

/**
 * Abstraction for search implementations to facilitate future migration from
 * PostgreSQL tsvector to OpenSearch/Elasticsearch.
 */
public interface SearchProvider {

    SearchResultResponse search(String query, String categorySlug, Pageable pageable);

    SearchResultResponse search(String query, String categorySlug, String brand,
                                Long minPrice, Long maxPrice, Pageable pageable);
}
