package com.instacommerce.fraud.client;

import java.util.UUID;

public record OrderSnapshot(UUID id, UUID userId) {
}
