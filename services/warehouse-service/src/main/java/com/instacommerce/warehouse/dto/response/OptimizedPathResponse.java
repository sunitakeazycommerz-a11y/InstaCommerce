package com.instacommerce.warehouse.dto.response;

import java.util.List;
import java.util.UUID;

public record OptimizedPathResponse(
    UUID storeId,
    double totalDistanceMeters,
    int estimatedPickSeconds,
    List<PickStepResponse> pickSteps,
    List<String> routeWaypoints
) {
}
