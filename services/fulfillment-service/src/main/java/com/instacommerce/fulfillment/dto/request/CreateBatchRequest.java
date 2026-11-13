package com.instacommerce.fulfillment.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateBatchRequest(
    @NotNull UUID storeId,
    String cartId,
    Integer maxOrders
) {
}
