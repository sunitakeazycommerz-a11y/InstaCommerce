package com.instacommerce.catalog.dto.response;

import java.util.UUID;

public record CategorySummaryResponse(
    UUID id,
    String name,
    String slug
) {
}
