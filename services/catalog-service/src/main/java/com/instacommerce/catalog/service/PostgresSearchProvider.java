package com.instacommerce.catalog.service;

import com.instacommerce.catalog.domain.model.Product;
import com.instacommerce.catalog.dto.mapper.ProductMapper;
import com.instacommerce.catalog.dto.response.ProductResponse;
import com.instacommerce.catalog.dto.response.SearchResultResponse;
import com.instacommerce.catalog.repository.SearchRepository;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Component
public class PostgresSearchProvider implements SearchProvider {
    private final SearchRepository searchRepository;

    public PostgresSearchProvider(SearchRepository searchRepository) {
        this.searchRepository = searchRepository;
    }

    @Override
    public SearchResultResponse search(String query, String categorySlug, Pageable pageable) {
        return search(query, categorySlug, null, null, null, pageable);
    }

    @Override
    public SearchResultResponse search(String query, String categorySlug, String brand,
                                       Long minPrice, Long maxPrice, Pageable pageable) {
        long start = System.nanoTime();
        Page<Product> resultPage = searchRepository.search(query, categorySlug, brand, minPrice, maxPrice, pageable);
        List<ProductResponse> results = resultPage.getContent().stream()
                .map(ProductMapper::toResponse)
                .toList();
        long tookMs = (System.nanoTime() - start) / 1_000_000;
        return new SearchResultResponse(results, resultPage.getTotalElements(), resultPage.getNumber(),
                resultPage.getSize(), query, tookMs);
    }
}
