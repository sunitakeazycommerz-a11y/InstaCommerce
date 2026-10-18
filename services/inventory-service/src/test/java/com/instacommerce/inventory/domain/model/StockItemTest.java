package com.instacommerce.inventory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.instacommerce.inventory.exception.InsufficientStockException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockItemTest {

    private StockItem stockItem;
    private UUID productId;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        productId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        stockItem = new StockItem();
        stockItem.setId(UUID.randomUUID());
        stockItem.setProductId(productId);
        stockItem.setStoreId(storeId);
        stockItem.setOnHand(50);
        stockItem.setReserved(10);
    }

    @Test
    @DisplayName("reserve() increments reserved count when available stock is sufficient")
    void reserveSucceedsWhenAvailableSufficient() {
        // available = onHand(50) - reserved(10) = 40
        stockItem.reserve(15);
        assertThat(stockItem.getReserved()).isEqualTo(25);
        assertThat(stockItem.getOnHand()).isEqualTo(50);
    }

    @Test
    @DisplayName("reserve() throws InsufficientStockException when requested quantity exceeds available")
    void reserveThrowsWhenExceedingAvailable() {
        // available = 40, requesting 45
        assertThatThrownBy(() -> stockItem.reserve(45))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(stockItem.getReserved()).isEqualTo(10);
    }

    @Test
    @DisplayName("reserve() rejects non-positive quantities")
    void reserveRejectsNonPositive() {
        assertThatThrownBy(() -> stockItem.reserve(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stockItem.reserve(-5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("confirm() decrements both onHand and reserved stock")
    void confirmSucceedsAndDeductsStock() {
        stockItem.confirm(10);
        assertThat(stockItem.getOnHand()).isEqualTo(40);
        assertThat(stockItem.getReserved()).isEqualTo(0);
    }

    @Test
    @DisplayName("confirm() throws InsufficientStockException when requested quantity exceeds reserved count")
    void confirmThrowsWhenExceedingReserved() {
        // reserved is 10, requesting 15
        assertThatThrownBy(() -> stockItem.confirm(15))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(stockItem.getOnHand()).isEqualTo(50);
        assertThat(stockItem.getReserved()).isEqualTo(10);
    }

    @Test
    @DisplayName("confirm() rejects non-positive quantities")
    void confirmRejectsNonPositive() {
        assertThatThrownBy(() -> stockItem.confirm(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stockItem.confirm(-2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("releaseReserved() decrements reserved count without changing onHand")
    void releaseReservedSucceeds() {
        stockItem.releaseReserved(5);
        assertThat(stockItem.getReserved()).isEqualTo(5);
        assertThat(stockItem.getOnHand()).isEqualTo(50);
    }

    @Test
    @DisplayName("releaseReserved() throws InsufficientStockException when release quantity exceeds reserved count")
    void releaseReservedThrowsWhenExceedingReserved() {
        // reserved is 10, attempting to release 20
        assertThatThrownBy(() -> stockItem.releaseReserved(20))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(stockItem.getReserved()).isEqualTo(10);
    }

    @Test
    @DisplayName("releaseReserved() rejects negative quantities")
    void releaseReservedRejectsNegative() {
        assertThatThrownBy(() -> stockItem.releaseReserved(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("releaseConfirmed() restores onHand stock upon cancellation/compensation")
    void releaseConfirmedRestoresOnHand() {
        stockItem.releaseConfirmed(10);
        assertThat(stockItem.getOnHand()).isEqualTo(60);
        assertThat(stockItem.getReserved()).isEqualTo(10);
    }

    @Test
    @DisplayName("releaseConfirmed() rejects negative quantities")
    void releaseConfirmedRejectsNegative() {
        assertThatThrownBy(() -> stockItem.releaseConfirmed(-5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Simultaneous split reservation and cancellation invariant prevents negative stock")
    void simultaneousSplitAndCancelPreventsNegativeStock() {
        // Issue #588: simulate an order item cancelled while a split reservation is in progress
        stockItem.setOnHand(10);
        stockItem.setReserved(10); // All 10 reserved

        // Attempting to confirm 15 should immediately be blocked by domain invariants
        assertThatThrownBy(() -> stockItem.confirm(15))
                .isInstanceOf(InsufficientStockException.class);

        // Attempting to release more than reserved (12) must fail without driving reserved negative
        assertThatThrownBy(() -> stockItem.releaseReserved(12))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(stockItem.getOnHand()).isGreaterThanOrEqualTo(0);
        assertThat(stockItem.getReserved()).isGreaterThanOrEqualTo(0);
        assertThat(stockItem.getReserved()).isLessThanOrEqualTo(stockItem.getOnHand());
    }
}
