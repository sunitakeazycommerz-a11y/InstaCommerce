package com.instacommerce.catalog.event;

import java.util.UUID;

public record ProductChangedEvent(
    UUID productId,
    String sku,
    String name,
    String slug,
    UUID categoryId,
    boolean active
) {
}
