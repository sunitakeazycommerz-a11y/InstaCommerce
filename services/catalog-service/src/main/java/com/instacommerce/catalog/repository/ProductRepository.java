package com.instacommerce.catalog.repository;

import com.instacommerce.catalog.domain.model.Product;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    @EntityGraph(attributePaths = {"category", "images"})
    Optional<Product> findByIdAndIsActiveTrue(UUID id);

    @EntityGraph(attributePaths = {"category", "images"})
    Page<Product> findByIsActiveTrue(Pageable pageable);

    @EntityGraph(attributePaths = {"category", "images"})
    Page<Product> findByCategory_SlugAndIsActiveTrue(String slug, Pageable pageable);

    @EntityGraph(attributePaths = {"category", "images"})
    Page<Product> findByCategory_IdAndIsActiveTrue(UUID categoryId, Pageable pageable);

    boolean existsBySku(String sku);

    boolean existsBySlug(String slug);
}
