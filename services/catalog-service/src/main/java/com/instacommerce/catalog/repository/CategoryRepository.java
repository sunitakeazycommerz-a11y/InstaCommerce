package com.instacommerce.catalog.repository;

import com.instacommerce.catalog.domain.model.Category;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findByIsActiveTrueOrderBySortOrderAscNameAsc();
}
