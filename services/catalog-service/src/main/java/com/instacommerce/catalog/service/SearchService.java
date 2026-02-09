package com.instacommerce.catalog.service;

import com.instacommerce.catalog.dto.response.AutocompleteResult;
import com.instacommerce.catalog.dto.response.SearchResultResponse;
import com.instacommerce.catalog.repository.SearchRepository;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// TODO: Migrate from PostgreSQL tsvector search to OpenSearch/Elasticsearch for production-grade
//  autocomplete, fuzzy matching, and scalable full-text search. The SearchProvider abstraction
//  is in place to facilitate this migration — implement an OpenSearchProvider and swap via config.
@Service
public class SearchService {
    private static final int MAX_PAGE_SIZE = 100;
    private final SearchProvider searchProvider;
    private final SearchRepository searchRepository;

    public SearchService(SearchProvider searchProvider, SearchRepository searchRepository) {
        this.searchProvider = searchProvider;
        this.searchRepository = searchRepository;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "search", key = "#query + '-' + #categorySlug + '-' + #brand + '-' + #minPrice + '-' + #maxPrice + '-' + #pageable.pageNumber + '-' + #pageable.pageSize")
    public SearchResultResponse search(String query, String categorySlug, String brand,
                                       Long minPrice, Long maxPrice, Pageable pageable) {
        Pageable sanitized = sanitize(pageable);
        if (query == null || query.isBlank()) {
            return new SearchResultResponse(List.of(), 0, sanitized.getPageNumber(), sanitized.getPageSize(), query, 0);
        }
        return searchProvider.search(query, categorySlug, brand, minPrice, maxPrice, sanitized);
    }

    @Transactional(readOnly = true)
    public List<AutocompleteResult> autocomplete(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }
        return searchRepository.autocomplete(prefix);
    }

    private Pageable sanitize(Pageable pageable) {
        int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        return PageRequest.of(pageable.getPageNumber(), size, pageable.getSort());
    }
}
