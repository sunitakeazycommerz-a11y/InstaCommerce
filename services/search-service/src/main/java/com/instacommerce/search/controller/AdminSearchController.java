package com.instacommerce.search.controller;

import com.instacommerce.search.service.SearchIndexService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/admin/search", "/api/v1/admin/search"})
public class AdminSearchController {
    private final SearchIndexService searchIndexService;

    public AdminSearchController(SearchIndexService searchIndexService) {
        this.searchIndexService = searchIndexService;
    }

    public record IndexDocumentRequest(
        @NotNull UUID productId,
        @NotBlank String name,
        String description,
        String brand,
        String category,
        @Min(0) long priceCents,
        String imageUrl,
        boolean inStock,
        UUID storeId
    ) {}

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void upsertDocument(@Valid @RequestBody IndexDocumentRequest request) {
        searchIndexService.upsertDocument(
                request.productId(),
                request.name(),
                request.description(),
                request.brand(),
                request.category(),
                request.priceCents(),
                request.imageUrl(),
                request.inStock(),
                request.storeId());
    }

    @PatchMapping("/documents/{productId}/stock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void updateStock(@PathVariable UUID productId, @RequestParam boolean inStock) {
        searchIndexService.updateStockStatus(productId, inStock);
    }

    @DeleteMapping("/documents/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteDocument(@PathVariable UUID productId) {
        searchIndexService.deleteDocument(productId);
    }
}
