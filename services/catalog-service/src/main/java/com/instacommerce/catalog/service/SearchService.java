package com.instacommerce.catalog.service;

import com.instacommerce.catalog.dto.response.AutocompleteResult;
import com.instacommerce.catalog.dto.response.SearchResultResponse;
import com.instacommerce.catalog.repository.SearchRepository;
import com.instacommerce.platform.web.PageableUtils;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SearchService {
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
        Pageable sanitized = PageableUtils.sanitize(pageable);
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
}
