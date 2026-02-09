package com.instacommerce.catalog.repository;

import com.instacommerce.catalog.domain.model.Product;
import com.instacommerce.catalog.dto.response.AutocompleteResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public class SearchRepository {
    @PersistenceContext
    private EntityManager entityManager;

    public Page<Product> search(String queryText, String categorySlug, Pageable pageable) {
        return search(queryText, categorySlug, null, null, null, pageable);
    }

    public Page<Product> search(String queryText, String categorySlug, String brand,
                                Long minPrice, Long maxPrice, Pageable pageable) {
        StringBuilder sql = new StringBuilder("""
            SELECT p.* FROM products p
            LEFT JOIN categories c ON p.category_id = c.id
            WHERE p.is_active = true
              AND p.search_vector @@ plainto_tsquery('english', :query)
              AND (:categorySlug IS NULL OR c.slug = :categorySlug)
            """);
        StringBuilder countSql = new StringBuilder("""
            SELECT count(*) FROM products p
            LEFT JOIN categories c ON p.category_id = c.id
            WHERE p.is_active = true
              AND p.search_vector @@ plainto_tsquery('english', :query)
              AND (:categorySlug IS NULL OR c.slug = :categorySlug)
            """);

        if (brand != null && !brand.isBlank()) {
            sql.append(" AND p.brand = :brand");
            countSql.append(" AND p.brand = :brand");
        }
        if (minPrice != null) {
            sql.append(" AND p.base_price_cents >= :minPrice");
            countSql.append(" AND p.base_price_cents >= :minPrice");
        }
        if (maxPrice != null) {
            sql.append(" AND p.base_price_cents <= :maxPrice");
            countSql.append(" AND p.base_price_cents <= :maxPrice");
        }

        sql.append(" ORDER BY ts_rank(p.search_vector, plainto_tsquery('english', :query)) DESC");
        sql.append(" LIMIT :limit OFFSET :offset");

        Query query = entityManager.createNativeQuery(sql.toString(), Product.class);
        query.setParameter("query", queryText);
        query.setParameter("categorySlug", categorySlug);
        query.setParameter("limit", pageable.getPageSize());
        query.setParameter("offset", pageable.getOffset());
        if (brand != null && !brand.isBlank()) {
            query.setParameter("brand", brand);
        }
        if (minPrice != null) {
            query.setParameter("minPrice", minPrice);
        }
        if (maxPrice != null) {
            query.setParameter("maxPrice", maxPrice);
        }

        @SuppressWarnings("unchecked")
        List<Product> results = query.getResultList();

        Query countQuery = entityManager.createNativeQuery(countSql.toString());
        countQuery.setParameter("query", queryText);
        countQuery.setParameter("categorySlug", categorySlug);
        if (brand != null && !brand.isBlank()) {
            countQuery.setParameter("brand", brand);
        }
        if (minPrice != null) {
            countQuery.setParameter("minPrice", minPrice);
        }
        if (maxPrice != null) {
            countQuery.setParameter("maxPrice", maxPrice);
        }
        Number total = (Number) countQuery.getSingleResult();
        return new PageImpl<>(results, pageable, total.longValue());
    }

    public List<AutocompleteResult> autocomplete(String prefix) {
        String sql = """
            SELECT p.id, p.name FROM products p
            WHERE p.is_active = true
              AND p.search_vector @@ to_tsquery('english', :tsquery)
            ORDER BY ts_rank(p.search_vector, to_tsquery('english', :tsquery)) DESC
            LIMIT 10
            """;
        Query query = entityManager.createNativeQuery(sql);
        String sanitized = prefix.replaceAll("[^a-zA-Z0-9 ]", "").trim();
        String tsquery = sanitized.replaceAll("\\s+", " & ") + ":*";
        query.setParameter("tsquery", tsquery);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(row -> new AutocompleteResult((UUID) row[0], (String) row[1]))
                .toList();
    }
}
