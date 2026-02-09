package com.instacommerce.order.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.instacommerce.order.domain.model.OrderStatus;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderStatusUpdateRequest(
    @NotNull OrderStatus status,
    String note
) {
}
