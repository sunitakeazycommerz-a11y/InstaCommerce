package com.instacommerce.catalog.service;

import com.instacommerce.catalog.domain.model.Category;
import com.instacommerce.catalog.domain.model.Product;
import com.instacommerce.catalog.dto.mapper.ProductMapper;
import com.instacommerce.catalog.dto.response.CategoryResponse;
import com.instacommerce.catalog.dto.response.ProductResponse;
import com.instacommerce.catalog.exception.CategoryNotFoundException;
import com.instacommerce.catalog.repository.CategoryRepository;
import com.instacommerce.catalog.repository.ProductRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {
    private static final int MAX_PAGE_SIZE = 100;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    public CategoryService(CategoryRepository categoryRepository, ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    @Cacheable("categories")
    public List<CategoryResponse> getCategoryTree() {
        List<Category> categories = categoryRepository.findByIsActiveTrueOrderBySortOrderAscNameAsc();
        Map<UUID, CategoryResponse> lookup = new LinkedHashMap<>();
        for (Category category : categories) {
            lookup.put(category.getId(), new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getParentId(),
                new ArrayList<>()));
        }
        List<CategoryResponse> roots = new ArrayList<>();
        for (Category category : categories) {
            CategoryResponse response = lookup.get(category.getId());
            if (category.getParentId() != null && lookup.containsKey(category.getParentId())) {
                lookup.get(category.getParentId()).children().add(response);
            } else {
                roots.add(response);
            }
        }
        return roots;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "categories", key = "#categoryId + '-' + #pageable.pageNumber + '-' + #pageable.pageSize")
    public Page<ProductResponse> getProductsByCategory(UUID categoryId, Pageable pageable) {
        if (!categoryRepository.existsById(categoryId)) {
            throw new CategoryNotFoundException(categoryId);
        }
        Pageable sanitized = sanitize(pageable);
        Page<Product> products = productRepository.findByCategory_IdAndIsActiveTrue(categoryId, sanitized);
        return products.map(ProductMapper::toResponse);
    }

    private Pageable sanitize(Pageable pageable) {
        int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        return PageRequest.of(pageable.getPageNumber(), size, pageable.getSort());
    }
}
