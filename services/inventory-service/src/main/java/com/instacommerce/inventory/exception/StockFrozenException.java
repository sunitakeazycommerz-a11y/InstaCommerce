package com.instacommerce.inventory.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class StockFrozenException extends ApiException {
    public StockFrozenException(UUID productId, UUID storeId) {
        super(HttpStatus.CONFLICT, "STOCK_FROZEN",
              "Stock for product " + productId + " at dark store " + storeId + " is frozen for cycle count audit");
    }
}
