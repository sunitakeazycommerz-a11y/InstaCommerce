package com.instacommerce.search.dto;

import java.util.UUID;

public record AutocompleteResult(
    String suggestion,
    String category,
    UUID productId
) {
}
