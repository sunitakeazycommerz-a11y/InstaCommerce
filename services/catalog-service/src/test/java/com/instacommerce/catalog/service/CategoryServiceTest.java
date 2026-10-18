package com.instacommerce.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.catalog.domain.model.Category;
import com.instacommerce.catalog.dto.response.CategoryResponse;
import com.instacommerce.catalog.repository.CategoryRepository;
import com.instacommerce.catalog.repository.ProductRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock private CategoryRepository categoryRepository;
    @Mock private ProductRepository productRepository;

    private CategoryService categoryService;

    @BeforeEach
    void setUp() {
        categoryService = new CategoryService(categoryRepository, productRepository);
    }

    @Test
    @DisplayName("getSubtree fetches entire category taxonomy sub-tree in a single prefix range query")
    void getSubtreeSingleRangeScan() {
        Category dairy = new Category();
        dairy.setId(UUID.randomUUID());
        dairy.setName("Dairy");
        dairy.setSlug("dairy");
        dairy.setPath("/groceries/dairy");
        dairy.setActive(true);

        Category milk = new Category();
        milk.setId(UUID.randomUUID());
        milk.setName("Milk");
        milk.setSlug("milk");
        milk.setPath("/groceries/dairy/milk");
        milk.setParentId(dairy.getId());
        milk.setActive(true);

        Category cheese = new Category();
        cheese.setId(UUID.randomUUID());
        cheese.setName("Cheese");
        cheese.setSlug("cheese");
        cheese.setPath("/groceries/dairy/cheese");
        cheese.setParentId(dairy.getId());
        cheese.setActive(true);

        when(categoryRepository.findSubtreeByPathPrefix("/groceries/dairy"))
                .thenReturn(List.of(dairy, milk, cheese));

        List<CategoryResponse> subtree = categoryService.getSubtree("/groceries/dairy");

        assertThat(subtree).hasSize(3);
        assertThat(subtree.get(0).name()).isEqualTo("Dairy");
        assertThat(subtree.get(0).path()).isEqualTo("/groceries/dairy");
        assertThat(subtree.get(1).name()).isEqualTo("Milk");
        assertThat(subtree.get(1).path()).isEqualTo("/groceries/dairy/milk");
        assertThat(subtree.get(2).name()).isEqualTo("Cheese");
        assertThat(subtree.get(2).path()).isEqualTo("/groceries/dairy/cheese");

        verify(categoryRepository).findSubtreeByPathPrefix("/groceries/dairy");
    }

    @Test
    @DisplayName("getCategoryTree populates materialized path in CategoryResponse")
    void getCategoryTreePopulatesPath() {
        Category root = new Category();
        root.setId(UUID.randomUUID());
        root.setName("Groceries");
        root.setSlug("groceries");
        root.setPath("/groceries");
        root.setActive(true);

        Category child = new Category();
        child.setId(UUID.randomUUID());
        child.setName("Beverages");
        child.setSlug("beverages");
        child.setPath("/groceries/beverages");
        child.setParentId(root.getId());
        child.setActive(true);

        when(categoryRepository.findByIsActiveTrueOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(root, child));

        List<CategoryResponse> tree = categoryService.getCategoryTree();

        assertThat(tree).hasSize(1);
        CategoryResponse rootResponse = tree.get(0);
        assertThat(rootResponse.name()).isEqualTo("Groceries");
        assertThat(rootResponse.path()).isEqualTo("/groceries");
        assertThat(rootResponse.children()).hasSize(1);
        assertThat(rootResponse.children().get(0).path()).isEqualTo("/groceries/beverages");
    }
}
