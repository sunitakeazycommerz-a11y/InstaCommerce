package com.instacommerce.catalog.dto.response;

import java.util.List;
import java.util.UUID;

public record CategoryResponse(
    UUID id,
    String name,
    String slug,
    String path,
    UUID parentId,
    List<CategoryResponse> children
) {
    public CategoryResponse(UUID id, String name, String slug, UUID parentId, List<CategoryResponse> children) {
        this(id, name, slug, "/" + slug, parentId, children);
    }
}
