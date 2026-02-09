package com.instacommerce.catalog.repository;

import com.instacommerce.catalog.domain.model.Product;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    Optional<Product> findByIdAndIsActiveTrue(UUID id);

    Page<Product> findByIsActiveTrue(Pageable pageable);

    Page<Product> findByCategory_SlugAndIsActiveTrue(String slug, Pageable pageable);

    Page<Product> findByCategory_IdAndIsActiveTrue(UUID categoryId, Pageable pageable);

    boolean existsBySku(String sku);

    boolean existsBySlug(String slug);
}
