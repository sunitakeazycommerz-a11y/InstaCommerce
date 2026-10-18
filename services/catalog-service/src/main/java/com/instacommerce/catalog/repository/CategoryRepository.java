package com.instacommerce.catalog.repository;

import com.instacommerce.catalog.domain.model.Category;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findByIsActiveTrueOrderBySortOrderAscNameAsc();

    @Query("SELECT c FROM Category c WHERE c.path LIKE :pathPrefix% AND c.isActive = true ORDER BY c.sortOrder ASC, c.name ASC")
    List<Category> findSubtreeByPathPrefix(@Param("pathPrefix") String pathPrefix);
}
