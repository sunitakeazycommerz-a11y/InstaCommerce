package com.instacommerce.catalog.service;

import com.instacommerce.catalog.domain.model.Category;
import com.instacommerce.catalog.domain.model.Product;
import com.instacommerce.catalog.dto.mapper.ProductMapper;
import com.instacommerce.catalog.dto.request.CreateCategoryRequest;
import com.instacommerce.catalog.dto.request.UpdateCategoryRequest;
import com.instacommerce.catalog.dto.response.CategoryResponse;
import com.instacommerce.catalog.dto.response.ProductResponse;
import com.instacommerce.catalog.exception.CategoryNotFoundException;
import com.instacommerce.catalog.exception.DuplicateCategoryException;
import com.instacommerce.catalog.exception.InvalidCategoryHierarchyException;
import com.instacommerce.catalog.repository.CategoryRepository;
import com.instacommerce.catalog.repository.ProductRepository;
import com.instacommerce.platform.web.PageableUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {
    public static final int MAX_CATEGORY_DEPTH = 5;

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
                category.getPath(),
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
    @Cacheable(value = "categories", key = "'subtree-' + #pathPrefix")
    public List<CategoryResponse> getSubtree(String pathPrefix) {
        String prefix = (pathPrefix != null && !pathPrefix.isBlank()) ? pathPrefix : "/";
        List<Category> categories = categoryRepository.findSubtreeByPathPrefix(prefix);
        return categories.stream()
                .map(c -> new CategoryResponse(c.getId(), c.getName(), c.getSlug(), c.getPath(), c.getParentId(), List.of()))
                .toList();
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "categories", key = "#categoryId + '-' + #pageable.pageNumber + '-' + #pageable.pageSize")
    public Page<ProductResponse> getProductsByCategory(UUID categoryId, Pageable pageable) {
        if (!categoryRepository.existsById(categoryId)) {
            throw new CategoryNotFoundException(categoryId);
        }
        Pageable sanitized = PageableUtils.sanitize(pageable);
        Page<Product> products = productRepository.findByCategory_IdAndIsActiveTrue(categoryId, sanitized);
        return products.map(ProductMapper::toResponse);
    }

    @Transactional
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        String slug = resolveSlug(request.slug(), request.name());
        if (categoryRepository.existsBySlugIgnoreCase(slug)) {
            throw new DuplicateCategoryException(slug);
        }

        HierarchyPosition position = resolveHierarchyPosition(null, request.parentId());

        Category category = new Category();
        category.setName(request.name().trim());
        category.setSlug(slug);
        category.setParentId(request.parentId());
        category.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
        category.setActive(request.active() == null || request.active());
        category.setDepth(position.depth());
        category.setPath(buildPath(position.parentPath(), slug));

        Category saved = categoryRepository.save(category);
        return toFlatResponse(saved);
    }

    @Transactional
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse updateCategory(UUID id, UpdateCategoryRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException(id));

        String nextSlug = category.getSlug();
        if (request.slug() != null && !request.slug().isBlank()) {
            String candidateSlug = resolveSlug(request.slug(), category.getName());
            if (!candidateSlug.equalsIgnoreCase(category.getSlug())
                    && categoryRepository.existsBySlugIgnoreCase(candidateSlug)) {
                throw new DuplicateCategoryException(candidateSlug);
            }
            nextSlug = candidateSlug;
        }

        UUID nextParentId = Boolean.TRUE.equals(request.clearParent())
                ? null
                : (request.parentId() != null ? request.parentId() : category.getParentId());

        HierarchyPosition position = resolveHierarchyPosition(id, nextParentId);

        if (request.name() != null && !request.name().isBlank()) {
            category.setName(request.name().trim());
        }
        category.setSlug(nextSlug);
        category.setParentId(nextParentId);
        category.setDepth(position.depth());
        category.setPath(buildPath(position.parentPath(), nextSlug));
        if (request.sortOrder() != null) {
            category.setSortOrder(request.sortOrder());
        }
        if (request.active() != null) {
            category.setActive(request.active());
        }

        Category saved = categoryRepository.save(category);
        recomputeDescendantPaths(saved);
        return toFlatResponse(saved);
    }

    @Transactional
    @CacheEvict(value = "categories", allEntries = true)
    public void deactivateCategory(UUID id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException(id));
        category.setActive(false);
        categoryRepository.save(category);
    }

    private HierarchyPosition resolveHierarchyPosition(UUID selfId, UUID parentId) {
        if (parentId == null) {
            return new HierarchyPosition(0, "");
        }
        if (selfId != null && selfId.equals(parentId)) {
            throw new InvalidCategoryHierarchyException("Category cannot be its own parent");
        }

        Set<UUID> visited = new HashSet<>();
        if (selfId != null) {
            visited.add(selfId);
        }

        Category immediateParent = categoryRepository.findById(parentId)
                .orElseThrow(() -> new CategoryNotFoundException(parentId));

        int depth = 1;
        UUID cursorId = immediateParent.getParentId();
        visited.add(immediateParent.getId());

        while (cursorId != null) {
            if (!visited.add(cursorId)) {
                throw new InvalidCategoryHierarchyException("Circular category parent relationship detected");
            }
            depth++;
            if (depth > MAX_CATEGORY_DEPTH) {
                throw new InvalidCategoryHierarchyException(
                        "Maximum category hierarchy depth of " + MAX_CATEGORY_DEPTH + " exceeded");
            }
            Category ancestor = categoryRepository.findById(cursorId)
                    .orElseThrow(() -> new CategoryNotFoundException(parentId));
            cursorId = ancestor.getParentId();
        }

        int maxDescendantSubtreeOffset = selfId != null ? computeMaxSubtreeDepthOffset(selfId) : 0;
        if (depth + maxDescendantSubtreeOffset > MAX_CATEGORY_DEPTH) {
            throw new InvalidCategoryHierarchyException(
                    "Maximum category hierarchy depth of " + MAX_CATEGORY_DEPTH + " exceeded for subtree");
        }

        String parentPath = immediateParent.getPath() != null && !immediateParent.getPath().isBlank()
                ? immediateParent.getPath()
                : "/" + immediateParent.getSlug();
        return new HierarchyPosition(depth, parentPath);
    }

    private int computeMaxSubtreeDepthOffset(UUID rootId) {
        Set<UUID> visited = new HashSet<>();
        visited.add(rootId);
        Queue<UUID> queue = new ArrayDeque<>();
        queue.add(rootId);
        int offset = 0;
        while (!queue.isEmpty()) {
            int levelSize = queue.size();
            boolean hasNextLevel = false;
            for (int i = 0; i < levelSize; i++) {
                UUID current = queue.poll();
                for (Category child : categoryRepository.findByParentId(current)) {
                    if (visited.add(child.getId())) {
                        queue.add(child.getId());
                        hasNextLevel = true;
                    }
                }
            }
            if (hasNextLevel) {
                offset++;
            }
        }
        return offset;
    }

    private void recomputeDescendantPaths(Category root) {
        Set<UUID> visited = new HashSet<>();
        visited.add(root.getId());
        Queue<Category> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Category parent = queue.poll();
            for (Category child : categoryRepository.findByParentId(parent.getId())) {
                if (!visited.add(child.getId())) {
                    continue;
                }
                child.setDepth(parent.getDepth() + 1);
                child.setPath(buildPath(parent.getPath(), child.getSlug()));
                categoryRepository.save(child);
                queue.add(child);
            }
        }
    }

    private String resolveSlug(String requestedSlug, String fallbackName) {
        String raw = (requestedSlug != null && !requestedSlug.isBlank()) ? requestedSlug : fallbackName;
        String slug = raw == null ? "" : raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "")
                .replaceAll("-{2,}", "-");
        if (slug.isBlank()) {
            throw new InvalidCategoryHierarchyException("Category slug cannot be blank");
        }
        return slug;
    }

    private String buildPath(String parentPath, String slug) {
        if (parentPath == null || parentPath.isBlank()) {
            return "/" + slug;
        }
        return parentPath.endsWith("/") ? parentPath + slug : parentPath + "/" + slug;
    }

    private CategoryResponse toFlatResponse(Category c) {
        return new CategoryResponse(c.getId(), c.getName(), c.getSlug(), c.getPath(), c.getParentId(), List.of());
    }

    private record HierarchyPosition(int depth, String parentPath) {}
}
