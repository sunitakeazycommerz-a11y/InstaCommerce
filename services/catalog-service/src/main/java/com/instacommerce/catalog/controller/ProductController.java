package com.instacommerce.catalog.controller;

import com.instacommerce.catalog.dto.response.ProductResponse;
import com.instacommerce.catalog.security.ClientIpResolver;
import com.instacommerce.catalog.service.ProductService;
import com.instacommerce.catalog.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ClientIpResolver clientIpResolver;

    public ProductController(ProductService productService, RateLimitService rateLimitService) {
        this(productService, rateLimitService, new ClientIpResolver());
    }

    @Autowired
    public ProductController(
            ProductService productService,
            RateLimitService rateLimitService,
            ClientIpResolver clientIpResolver) {
        this.productService = productService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver != null ? clientIpResolver : new ClientIpResolver();
    }

    @GetMapping("/{id}")
    public ProductResponse getProduct(@PathVariable UUID id, HttpServletRequest request) {
        rateLimitService.checkProduct(clientIpResolver.resolve(request));
        return productService.getProduct(id);
    }

    @GetMapping
    public Page<ProductResponse> listProducts(@RequestParam(required = false) String category,
                                              Pageable pageable,
                                              HttpServletRequest request) {
        rateLimitService.checkProduct(clientIpResolver.resolve(request));
        return productService.listProducts(category, pageable);
    }
}
