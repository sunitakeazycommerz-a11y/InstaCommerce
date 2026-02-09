package com.instacommerce.catalog.controller;

import com.instacommerce.catalog.dto.response.AutocompleteResult;
import com.instacommerce.catalog.dto.response.SearchResultResponse;
import com.instacommerce.catalog.service.RateLimitService;
import com.instacommerce.catalog.service.SearchService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/search")
public class SearchController {
    private final SearchService searchService;
    private final RateLimitService rateLimitService;

    public SearchController(SearchService searchService, RateLimitService rateLimitService) {
        this.searchService = searchService;
        this.rateLimitService = rateLimitService;
    }

    @GetMapping
    public SearchResultResponse search(@RequestParam("q") String query,
                                       @RequestParam(required = false) String category,
                                       @RequestParam(required = false) String brand,
                                       @RequestParam(required = false) Long minPrice,
                                       @RequestParam(required = false) Long maxPrice,
                                       Pageable pageable,
                                       HttpServletRequest request) {
        rateLimitService.checkSearch(resolveIp(request));
        return searchService.search(query, category, brand, minPrice, maxPrice, pageable);
    }

    @GetMapping("/autocomplete")
    public List<AutocompleteResult> autocomplete(@RequestParam("q") String query,
                                                 HttpServletRequest request) {
        rateLimitService.checkSearch(resolveIp(request));
        return searchService.autocomplete(query);
    }

    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
