package com.instacommerce.inventory.dto.mapper;

import com.instacommerce.inventory.domain.model.Reservation;
import com.instacommerce.inventory.domain.model.ReservationLineItem;
import com.instacommerce.inventory.domain.model.StockItem;
import com.instacommerce.inventory.dto.response.ReserveResponse;
import com.instacommerce.inventory.dto.response.ReservedItemResponse;
import com.instacommerce.inventory.dto.response.StockCheckItemResponse;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class InventoryMapper {
    private InventoryMapper() {
    }

    public static StockCheckItemResponse toStockCheckItemResponse(StockItem stockItem, int requestedQuantity) {
        int available = stockItem.getOnHand() - stockItem.getReserved();
        boolean sufficient = available >= requestedQuantity;
        return new StockCheckItemResponse(stockItem.getProductId(), available, stockItem.getOnHand(), sufficient);
    }

    public static ReserveResponse toReserveResponse(Reservation reservation) {
        List<ReservedItemResponse> items = reservation.getLineItems().stream()
            .filter(Objects::nonNull)
            .map(InventoryMapper::toReservedItem)
            .collect(Collectors.toList());
        return new ReserveResponse(reservation.getId(), reservation.getExpiresAt(), items);
    }

    private static ReservedItemResponse toReservedItem(ReservationLineItem item) {
        return new ReservedItemResponse(item.getProductId(), item.getQuantity());
    }
}
