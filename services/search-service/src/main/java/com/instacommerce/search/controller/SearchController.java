package com.instacommerce.search.controller;

import com.instacommerce.search.dto.AutocompleteResult;
import com.instacommerce.search.dto.SearchResponse;
import com.instacommerce.search.service.SearchService;
import com.instacommerce.search.service.TrendingService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/search")
@Validated
public class SearchController {

    private final SearchService searchService;
    private final TrendingService trendingService;

    public SearchController(SearchService searchService, TrendingService trendingService) {
        this.searchService = searchService;
        this.trendingService = trendingService;
    }

    @GetMapping
    public ResponseEntity<SearchResponse> search(
            @RequestParam @Size(min = 1, max = 256, message = "Query must be between 1 and 256 characters") String query,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @Min(0) Long minPriceCents,
            @RequestParam(required = false) @Min(0) Long maxPriceCents,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        SearchResponse response = searchService.search(query, brand, category, minPriceCents, maxPriceCents, page, size);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/autocomplete")
    public ResponseEntity<List<AutocompleteResult>> autocomplete(
            @RequestParam @Size(min = 1, max = 128, message = "Prefix must be between 1 and 128 characters") String prefix,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        List<AutocompleteResult> results = searchService.autocomplete(prefix, limit);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/trending")
    public ResponseEntity<List<String>> trending(
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        List<String> trending = trendingService.getTrending(limit);
        return ResponseEntity.ok(trending);
    }
}
