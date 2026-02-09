package com.instacommerce.search.repository;

import com.instacommerce.search.domain.model.SearchDocument;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SearchDocumentRepository extends JpaRepository<SearchDocument, UUID> {

    Optional<SearchDocument> findByProductId(UUID productId);

    void deleteByProductId(UUID productId);

    @Query(value = """
            SELECT sd.*, ts_rank(sd.search_vector, plainto_tsquery('english', :query)) AS rank
            FROM search_documents sd
            WHERE sd.search_vector @@ plainto_tsquery('english', :query)
              AND sd.in_stock = TRUE
              AND (:brand IS NULL OR sd.brand = :brand)
              AND (:category IS NULL OR sd.category = :category)
              AND (:minPrice IS NULL OR sd.price_cents >= :minPrice)
              AND (:maxPrice IS NULL OR sd.price_cents <= :maxPrice)
            ORDER BY rank DESC
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM search_documents sd
            WHERE sd.search_vector @@ plainto_tsquery('english', :query)
              AND sd.in_stock = TRUE
              AND (:brand IS NULL OR sd.brand = :brand)
              AND (:category IS NULL OR sd.category = :category)
              AND (:minPrice IS NULL OR sd.price_cents >= :minPrice)
              AND (:maxPrice IS NULL OR sd.price_cents <= :maxPrice)
            """,
            nativeQuery = true)
    Page<Object[]> fullTextSearch(@Param("query") String query,
                                 @Param("brand") String brand,
                                 @Param("category") String category,
                                 @Param("minPrice") Long minPrice,
                                 @Param("maxPrice") Long maxPrice,
                                 Pageable pageable);

    @Query(value = """
            SELECT sd.name AS suggestion, sd.category, sd.product_id
            FROM search_documents sd
            WHERE sd.name ILIKE :prefix || '%' ESCAPE '\\'
              AND sd.in_stock = TRUE
            ORDER BY sd.name
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> autocomplete(@Param("prefix") String prefix, @Param("limit") int limit);

    @Query(value = """
            SELECT sd.brand, COUNT(*) AS cnt
            FROM search_documents sd
            WHERE sd.search_vector @@ plainto_tsquery('english', :query)
              AND sd.in_stock = TRUE
            GROUP BY sd.brand
            ORDER BY cnt DESC
            """, nativeQuery = true)
    List<Object[]> facetByBrand(@Param("query") String query);

    @Query(value = """
            SELECT sd.category, COUNT(*) AS cnt
            FROM search_documents sd
            WHERE sd.search_vector @@ plainto_tsquery('english', :query)
              AND sd.in_stock = TRUE
            GROUP BY sd.category
            ORDER BY cnt DESC
            """, nativeQuery = true)
    List<Object[]> facetByCategory(@Param("query") String query);
}
