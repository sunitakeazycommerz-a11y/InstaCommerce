package com.instacommerce.catalog.service;

import com.instacommerce.catalog.domain.model.Category;
import com.instacommerce.catalog.domain.model.Product;
import com.instacommerce.catalog.dto.mapper.ProductMapper;
import com.instacommerce.catalog.dto.request.CreateProductRequest;
import com.instacommerce.catalog.dto.request.UpdateProductRequest;
import com.instacommerce.catalog.dto.response.ProductResponse;
import com.instacommerce.catalog.exception.CategoryNotFoundException;
import com.instacommerce.catalog.exception.DuplicateSkuException;
import com.instacommerce.catalog.exception.ProductNotFoundException;
import com.instacommerce.catalog.repository.CategoryRepository;
import com.instacommerce.catalog.repository.ProductRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {
    private static final int MAX_PAGE_SIZE = 100;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final OutboxService outboxService;
    private final AuditLogService auditLogService;

    public ProductService(ProductRepository productRepository,
                          CategoryRepository categoryRepository,
                          OutboxService outboxService,
                          AuditLogService auditLogService) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.outboxService = outboxService;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "products", key = "#id")
    public ProductResponse getProduct(UUID id) {
        Product product = productRepository.findByIdAndIsActiveTrue(id)
            .orElseThrow(() -> new ProductNotFoundException(id));
        return ProductMapper.toResponse(product);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> listProducts(String categorySlug, Pageable pageable) {
        Pageable sanitized = sanitize(pageable);
        Page<Product> products = categorySlug == null || categorySlug.isBlank()
            ? productRepository.findByIsActiveTrue(sanitized)
            : productRepository.findByCategory_SlugAndIsActiveTrue(categorySlug, sanitized);
        return products.map(ProductMapper::toResponse);
    }

    @Transactional
    @CacheEvict(value = "products", key = "#result.id()")
    public ProductResponse createProduct(CreateProductRequest request) {
        if (productRepository.existsBySku(request.sku())) {
            throw new DuplicateSkuException(request.sku());
        }
        Category category = categoryRepository.findById(request.categoryId())
            .orElseThrow(() -> new CategoryNotFoundException(request.categoryId()));
        Product product = new Product();
        product.setSku(request.sku());
        product.setName(request.name());
        product.setSlug(resolveUniqueSlug(request.name(), null));
        product.setDescription(request.description());
        product.setCategory(category);
        product.setBrand(request.brand());
        product.setBasePriceCents(request.basePriceCents());
        product.setCurrency(request.currency());
        product.setUnit(request.unit());
        product.setUnitValue(request.unitValue());
        product.setWeightGrams(request.weightGrams());
        product.replaceImages(ProductMapper.toImages(request.images(), product));
        Product saved = productRepository.save(product);
        outboxService.recordProductEvent(saved, "ProductCreated");
        auditLogService.log(null,
            "PRODUCT_CREATED",
            "Product",
            saved.getId().toString(),
            Map.of("sku", saved.getSku(), "active", saved.isActive()));
        return ProductMapper.toResponse(saved);
    }

    @Transactional
    @CacheEvict(value = "products", key = "#id")
    public ProductResponse updateProduct(UUID id, UpdateProductRequest request) {
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new ProductNotFoundException(id));
        if (request.sku() != null && !request.sku().equals(product.getSku())) {
            if (productRepository.existsBySku(request.sku())) {
                throw new DuplicateSkuException(request.sku());
            }
            product.setSku(request.sku());
        }
        if (request.name() != null && !request.name().equals(product.getName())) {
            product.setName(request.name());
            product.setSlug(resolveUniqueSlug(request.name(), product.getSlug()));
        }
        if (request.description() != null) {
            product.setDescription(request.description());
        }
        if (request.categoryId() != null) {
            Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new CategoryNotFoundException(request.categoryId()));
            product.setCategory(category);
        }
        if (request.brand() != null) {
            product.setBrand(request.brand());
        }
        if (request.basePriceCents() != null) {
            product.setBasePriceCents(request.basePriceCents());
        }
        if (request.currency() != null) {
            product.setCurrency(request.currency());
        }
        if (request.unit() != null) {
            product.setUnit(request.unit());
        }
        if (request.unitValue() != null) {
            product.setUnitValue(request.unitValue());
        }
        if (request.weightGrams() != null) {
            product.setWeightGrams(request.weightGrams());
        }
        if (request.isActive() != null) {
            product.setActive(request.isActive());
        }
        if (request.images() != null) {
            product.replaceImages(ProductMapper.toImages(request.images(), product));
        }
        Product saved = productRepository.save(product);
        outboxService.recordProductEvent(saved, "ProductUpdated");
        auditLogService.log(null,
            "PRODUCT_UPDATED",
            "Product",
            saved.getId().toString(),
            Map.of("sku", saved.getSku(), "active", saved.isActive()));
        return ProductMapper.toResponse(saved);
    }

    @Transactional
    @CacheEvict(value = "products", key = "#id")
    public void deleteProduct(UUID id) {
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new ProductNotFoundException(id));
        product.setActive(false);
        Product saved = productRepository.save(product);
        outboxService.recordProductEvent(saved, "ProductDeactivated");
        auditLogService.log(null,
            "PRODUCT_DEACTIVATED",
            "Product",
            saved.getId().toString(),
            Map.of("sku", saved.getSku(), "active", saved.isActive()));
    }

    private Pageable sanitize(Pageable pageable) {
        int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        return PageRequest.of(pageable.getPageNumber(), size, pageable.getSort());
    }

    private String resolveUniqueSlug(String name, String currentSlug) {
        String baseSlug = slugify(name);
        if (baseSlug.isBlank()) {
            baseSlug = "product";
        }
        if (currentSlug != null && currentSlug.equals(baseSlug)) {
            return currentSlug;
        }
        String candidate = baseSlug;
        int suffix = 1;
        while (productRepository.existsBySlug(candidate)) {
            candidate = baseSlug + "-" + suffix++;
        }
        return candidate;
    }

    private String slugify(String input) {
        String slug = input == null ? "" : input.toLowerCase(Locale.ROOT);
        slug = slug.replaceAll("[^a-z0-9]+", "-");
        slug = slug.replaceAll("(^-|-$)", "");
        return slug.replaceAll("-{2,}", "-");
    }
}
