package com.instacommerce.catalog.controller;

import com.instacommerce.catalog.dto.response.ProductResponse;
import com.instacommerce.catalog.service.ProductService;
import com.instacommerce.catalog.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/products")
public class ProductController {
    private final ProductService productService;
    private final RateLimitService rateLimitService;

    public ProductController(ProductService productService, RateLimitService rateLimitService) {
        this.productService = productService;
        this.rateLimitService = rateLimitService;
    }

    @GetMapping("/{id}")
    public ProductResponse getProduct(@PathVariable UUID id, HttpServletRequest request) {
        rateLimitService.checkProduct(resolveIp(request));
        return productService.getProduct(id);
    }

    @GetMapping
    public Page<ProductResponse> listProducts(@RequestParam(required = false) String category,
                                              Pageable pageable,
                                              HttpServletRequest request) {
        rateLimitService.checkProduct(resolveIp(request));
        return productService.listProducts(category, pageable);
    }

    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
