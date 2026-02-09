package com.instacommerce.catalog.controller;

import com.instacommerce.catalog.dto.response.CategoryResponse;
import com.instacommerce.catalog.dto.response.ProductResponse;
import com.instacommerce.catalog.service.CategoryService;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/categories")
public class CategoryController {
    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public List<CategoryResponse> getCategories() {
        return categoryService.getCategoryTree();
    }

    @GetMapping("/{id}/products")
    public Page<ProductResponse> getCategoryProducts(@PathVariable UUID id, Pageable pageable) {
        return categoryService.getProductsByCategory(id, pageable);
    }
}
