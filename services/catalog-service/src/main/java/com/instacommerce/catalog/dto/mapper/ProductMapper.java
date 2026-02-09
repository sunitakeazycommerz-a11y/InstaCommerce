package com.instacommerce.catalog.dto.mapper;

import com.instacommerce.catalog.domain.model.Category;
import com.instacommerce.catalog.domain.model.Product;
import com.instacommerce.catalog.domain.model.ProductImage;
import com.instacommerce.catalog.dto.request.ProductImageRequest;
import com.instacommerce.catalog.dto.response.CategorySummaryResponse;
import com.instacommerce.catalog.dto.response.ProductImageResponse;
import com.instacommerce.catalog.dto.response.ProductResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ProductMapper {
    private ProductMapper() {
    }

    public static ProductResponse toResponse(Product product) {
        CategorySummaryResponse category = toCategorySummary(product.getCategory());
        List<ProductImageResponse> images = toImageResponses(product.getImages());
        return new ProductResponse(
            product.getId(),
            product.getSku(),
            product.getName(),
            product.getSlug(),
            product.getDescription(),
            category,
            product.getBrand(),
            product.getBasePriceCents(),
            product.getCurrency(),
            product.getUnit(),
            product.getUnitValue(),
            product.getWeightGrams(),
            images,
            product.isActive(),
            product.getCreatedAt());
    }

    public static CategorySummaryResponse toCategorySummary(Category category) {
        if (category == null) {
            return null;
        }
        return new CategorySummaryResponse(category.getId(), category.getName(), category.getSlug());
    }

    public static List<ProductImageResponse> toImageResponses(List<ProductImage> images) {
        if (images == null) {
            return List.of();
        }
        return images.stream()
            .filter(Objects::nonNull)
            .map(image -> new ProductImageResponse(image.getUrl(), image.getAltText(), image.isPrimary()))
            .collect(Collectors.toList());
    }

    public static List<ProductImage> toImages(List<ProductImageRequest> requests, Product product) {
        if (requests == null) {
            return List.of();
        }
        List<ProductImage> images = new ArrayList<>();
        int sortOrder = 0;
        for (ProductImageRequest request : requests) {
            ProductImage image = new ProductImage();
            image.setProduct(product);
            image.setUrl(request.url());
            image.setAltText(request.altText());
            image.setPrimary(Boolean.TRUE.equals(request.isPrimary()));
            image.setSortOrder(sortOrder++);
            images.add(image);
        }
        return images;
    }
}
