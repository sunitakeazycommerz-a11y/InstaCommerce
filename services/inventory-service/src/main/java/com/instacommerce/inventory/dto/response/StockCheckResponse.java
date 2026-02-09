package com.instacommerce.inventory.dto.response;

import java.util.List;

public record StockCheckResponse(
    List<StockCheckItemResponse> items
) {
}
