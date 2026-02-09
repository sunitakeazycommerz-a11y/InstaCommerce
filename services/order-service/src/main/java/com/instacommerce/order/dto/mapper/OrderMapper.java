package com.instacommerce.order.dto.mapper;

import com.instacommerce.order.domain.model.Order;
import com.instacommerce.order.domain.model.OrderItem;
import com.instacommerce.order.domain.model.OrderStatusHistory;
import com.instacommerce.order.dto.response.OrderItemResponse;
import com.instacommerce.order.dto.response.OrderResponse;
import com.instacommerce.order.dto.response.OrderStatusResponse;
import com.instacommerce.order.dto.response.OrderStatusTimelineResponse;
import com.instacommerce.order.dto.response.OrderSummaryResponse;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class OrderMapper {
    private OrderMapper() {
    }

    public static OrderResponse toResponse(Order order, List<OrderStatusHistory> history) {
        return new OrderResponse(
            order.getId(),
            order.getUserId(),
            order.getStoreId(),
            order.getStatus().name(),
            order.getItems().stream().map(OrderMapper::toItem).toList(),
            order.getSubtotalCents(),
            order.getDiscountCents(),
            order.getTotalCents(),
            order.getCurrency(),
            order.getCouponCode(),
            order.getCreatedAt(),
            toTimeline(history));
    }

    public static OrderSummaryResponse toSummary(Order order) {
        return new OrderSummaryResponse(
            order.getId(),
            order.getStatus().name(),
            order.getTotalCents(),
            order.getCurrency(),
            order.getCreatedAt());
    }

    public static OrderStatusResponse toStatusResponse(Order order, List<OrderStatusHistory> history) {
        return new OrderStatusResponse(order.getId(), order.getStatus().name(), toTimeline(history));
    }

    private static OrderItemResponse toItem(OrderItem item) {
        return new OrderItemResponse(
            item.getProductId(),
            item.getProductName(),
            item.getProductSku(),
            item.getQuantity(),
            item.getUnitPriceCents(),
            item.getLineTotalCents());
    }

    private static List<OrderStatusTimelineResponse> toTimeline(List<OrderStatusHistory> history) {
        if (history == null) {
            return List.of();
        }
        return history.stream()
            .filter(Objects::nonNull)
            .map(entry -> new OrderStatusTimelineResponse(
                entry.getFromStatus() != null ? entry.getFromStatus().name() : null,
                entry.getToStatus().name(),
                entry.getCreatedAt(),
                entry.getNote()))
            .collect(Collectors.toList());
    }
}
