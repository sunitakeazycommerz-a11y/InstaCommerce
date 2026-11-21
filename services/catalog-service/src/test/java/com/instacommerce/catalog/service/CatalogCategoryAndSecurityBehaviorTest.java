package com.instacommerce.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.catalog.domain.model.Category;
import com.instacommerce.catalog.domain.model.Product;
import com.instacommerce.catalog.dto.request.CreateCategoryRequest;
import com.instacommerce.catalog.dto.request.CreateProductRequest;
import com.instacommerce.catalog.dto.request.UpdateCategoryRequest;
import com.instacommerce.catalog.dto.response.CategoryResponse;
import com.instacommerce.catalog.dto.response.ProductResponse;
import com.instacommerce.catalog.exception.GlobalExceptionHandler;
import com.instacommerce.catalog.exception.InvalidCategoryHierarchyException;
import com.instacommerce.catalog.exception.ProductNotFoundException;
import com.instacommerce.catalog.exception.TraceIdProvider;
import com.instacommerce.catalog.repository.CategoryRepository;
import com.instacommerce.catalog.repository.ProductRepository;
import com.instacommerce.catalog.security.InternalServiceAuthFilter;
import com.instacommerce.catalog.security.JwtAuthenticationFilter;
import com.instacommerce.catalog.security.JwtService;
import com.instacommerce.catalog.exception.TraceIdProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class CatalogCategoryAndSecurityBehaviorTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("CategoryService rejects self-parent and circular parent-child hierarchies")
    void categoryServiceRejectsCircularHierarchy() {
        CategoryRepository categoryRepo = mock(CategoryRepository.class);
        ProductRepository productRepo = mock(ProductRepository.class);
        CategoryService service = new CategoryService(categoryRepo, productRepo);

        UUID catA = UUID.randomUUID();
        UUID catB = UUID.randomUUID();

        Category a = new Category();
        a.setId(catA);
        a.setName("Groceries");
        a.setSlug("groceries");
        a.setPath("/groceries");
        a.setDepth(0);

        Category b = new Category();
        b.setId(catB);
        b.setName("Dairy");
        b.setSlug("dairy");
        b.setParentId(catA);
        b.setPath("/groceries/dairy");
        b.setDepth(1);

        when(categoryRepo.findById(catA)).thenReturn(Optional.of(a));
        when(categoryRepo.findById(catB)).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.updateCategory(catA, new UpdateCategoryRequest(null, null, catA, false, null, null)))
                .isInstanceOf(InvalidCategoryHierarchyException.class)
                .hasMessageContaining("own parent");

        assertThatThrownBy(() -> service.updateCategory(catA, new UpdateCategoryRequest(null, null, catB, false, null, null)))
                .isInstanceOf(InvalidCategoryHierarchyException.class)
                .hasMessageContaining("Circular category parent");
    }

    @Test
    @DisplayName("CategoryService enforces maximum hierarchy depth and recomputes descendant paths on parent change")
    void categoryServiceEnforcesMaxDepthAndUpdatesDescendantPaths() {
        CategoryRepository categoryRepo = mock(CategoryRepository.class);
        ProductRepository productRepo = mock(ProductRepository.class);
        CategoryService service = new CategoryService(categoryRepo, productRepo);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        UUID id4 = UUID.randomUUID();
        UUID id5 = UUID.randomUUID();
        UUID id6 = UUID.randomUUID();

        Category c0 = category(id1, "c0", null, "/c0", 0);
        Category c1 = category(id2, "c1", id1, "/c0/c1", 1);
        Category c2 = category(id3, "c2", id2, "/c0/c1/c2", 2);
        Category c3 = category(id4, "c3", id3, "/c0/c1/c2/c3", 3);
        Category c4 = category(id5, "c4", id4, "/c0/c1/c2/c3/c4", 4);
        Category c5 = category(id6, "c5", id5, "/c0/c1/c2/c3/c4/c5", 5);

        when(categoryRepo.findById(id1)).thenReturn(Optional.of(c0));
        when(categoryRepo.findById(id2)).thenReturn(Optional.of(c1));
        when(categoryRepo.findById(id3)).thenReturn(Optional.of(c2));
        when(categoryRepo.findById(id4)).thenReturn(Optional.of(c3));
        when(categoryRepo.findById(id5)).thenReturn(Optional.of(c4));
        when(categoryRepo.findById(id6)).thenReturn(Optional.of(c5));

        assertThatThrownBy(() -> service.createCategory(
                new CreateCategoryRequest("TooDeep", "too-deep", id6, 0, true)))
                .isInstanceOf(InvalidCategoryHierarchyException.class)
                .hasMessageContaining("Maximum category hierarchy depth");

        Category rootNew = category(UUID.randomUUID(), "fresh", null, "/fresh", 0);
        Category child = category(UUID.randomUUID(), "milk", rootNew.getId(), "/old/milk", 1);
        when(categoryRepo.findById(rootNew.getId())).thenReturn(Optional.of(rootNew));
        when(categoryRepo.findByParentId(rootNew.getId())).thenReturn(List.of(child));
        when(categoryRepo.findByParentId(child.getId())).thenReturn(List.of());
        when(categoryRepo.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

        CategoryResponse updated = service.updateCategory(
                rootNew.getId(), new UpdateCategoryRequest("Fresh", "fresh-new", null, true, 1, true));
        assertThat(updated.path()).isEqualTo("/fresh-new");
        assertThat(child.getPath()).isEqualTo("/fresh-new/milk");
    }

    @Test
    @DisplayName("ProductService exposes storeId and scopes active product listings to requested store")
    void productServiceFiltersActiveByStoreAndExposesStoreId() {
        ProductRepository productRepo = mock(ProductRepository.class);
        CategoryRepository categoryRepo = mock(CategoryRepository.class);
        OutboxService outboxService = mock(OutboxService.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        ProductService service = new ProductService(productRepo, categoryRepo, outboxService, auditLogService);

        UUID storeId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        Category category = category(categoryId, "dairy", null, "/dairy", 0);

        when(productRepo.existsBySku("MILK-STORE")).thenReturn(false);
        when(categoryRepo.findById(categoryId)).thenReturn(Optional.of(category));
        when(productRepo.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        ProductResponse created = service.createProduct(new CreateProductRequest(
                "MILK-STORE", "Farm Milk", "Fresh", categoryId, "Amul", 6000L, "INR",
                "L", null, 1000, List.of(), false, "CHILLED", 500, "L", storeId));

        assertThat(created.storeId()).isEqualTo(storeId);

        Product activeProduct = new Product();
        activeProduct.setId(created.id());
        activeProduct.setSku("MILK-STORE");
        activeProduct.setName("Farm Milk");
        activeProduct.setSlug("farm-milk");
        activeProduct.setCategory(category);
        activeProduct.setBasePriceCents(6000L);
        activeProduct.setStoreId(storeId);
        activeProduct.setActive(true);

        when(productRepo.findActiveByOptionalCategoryAndStore(eq("dairy"), eq(storeId), any()))
                .thenReturn(new PageImpl<>(List.of(activeProduct)));

        Page<ProductResponse> page = service.listProducts("dairy", storeId, PageRequest.of(0, 500));
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().storeId()).isEqualTo(storeId);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepo).findActiveByOptionalCategoryAndStore(
                eq("dairy"), eq(storeId), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("JwtAuthenticationFilter preserves ROLE_INTERNAL_SERVICE granted by InternalServiceAuthFilter")
    void jwtFilterPreservesInternalServiceRole() throws Exception {
        JwtService jwtService = mock(JwtService.class);
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(
                jwtService, new TraceIdProvider(), new ObjectMapper());
        InternalServiceAuthFilter internalFilter = new InternalServiceAuthFilter("secret-internal-token", Map.of());

        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(UUID.randomUUID().toString());
        Jws<Claims> jws = mock();
        when(jws.getPayload()).thenReturn(claims);
        when(jwtService.validateAccessToken("valid-jwt")).thenReturn(jws);
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_USER"))).when(jwtService).extractAuthorities(claims);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/products");
        request.addHeader("X-Internal-Service", "order-service");
        request.addHeader("X-Internal-Token", "secret-internal-token");
        request.addHeader("Authorization", "Bearer valid-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain noopChain = (req, res) -> {};
        internalFilter.doFilter(request, response, noopChain);
        jwtFilter.doFilter(request, response, noopChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_INTERNAL_SERVICE");
    }

    @Test
    @DisplayName("GlobalExceptionHandler emits RFC 7807 ProblemDetail via ProblemDetailFactory")
    void globalExceptionHandlerEmitsProblemDetail() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/products/123");
        request.addHeader("X-Trace-Id", "trace-cat-404");

        UUID missingId = UUID.randomUUID();
        ResponseEntity<ProblemDetail> response = handler.handleApiException(
                new ProductNotFoundException(missingId), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getProperties())
                .containsEntry("code", "PRODUCT_NOT_FOUND")
                .containsEntry("traceId", "trace-cat-404");
        assertThat(problem.getType().toString()).isEqualTo("urn:instacommerce:problem:product_not_found");
    }

    private static Category category(UUID id, String slug, UUID parentId, String path, int depth) {
        Category c = new Category();
        c.setId(id);
        c.setName(slug);
        c.setSlug(slug);
        c.setParentId(parentId);
        c.setPath(path);
        c.setDepth(depth);
        c.setActive(true);
        return c;
    }
}
