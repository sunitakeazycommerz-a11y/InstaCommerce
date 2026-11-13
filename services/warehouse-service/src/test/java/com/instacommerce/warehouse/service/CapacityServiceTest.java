package com.instacommerce.warehouse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.warehouse.domain.model.Store;
import com.instacommerce.warehouse.domain.model.StoreCapacity;
import com.instacommerce.warehouse.dto.response.CapacityResponse;
import com.instacommerce.warehouse.exception.CapacityExceededException;
import com.instacommerce.warehouse.repository.StoreCapacityRepository;
import com.instacommerce.warehouse.repository.StoreRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CapacityServiceTest {

    @Mock
    private StoreCapacityRepository capacityRepository;

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private OutboxService outboxService;

    @InjectMocks
    private CapacityService capacityService;

    private Store buildStore(UUID id, int hourlyCap, int maxConcurrent, int currentPending) {
        Store store = new Store();
        store.setId(id);
        store.setTimezone("UTC");
        store.setCapacityOrdersPerHour(hourlyCap);
        store.setMaxConcurrentOrders(maxConcurrent);
        store.setCurrentPendingOrders(currentPending);
        return store;
    }

    @Test
    @DisplayName("getStoreCapacity reports NORMAL, THROTTLED, and PAUSED states based on concurrency and hourly limit")
    void getStoreCapacityReportsThrottleStates() {
        UUID storeId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.of(2026, 11, 13, 14, 15);
        Store normalStore = buildStore(storeId, 50, 100, 20);
        StoreCapacity cap = new StoreCapacity();
        cap.setStore(normalStore);
        cap.setDate(LocalDate.of(2026, 11, 13));
        cap.setHour(14);
        cap.setCurrentOrders(10);
        cap.setMaxOrders(50);

        when(storeRepository.findById(storeId)).thenReturn(Optional.of(normalStore));
        when(capacityRepository.findByStoreIdAndDateAndHour(eq(storeId), any(LocalDate.class), anyInt()))
            .thenReturn(Optional.of(cap));

        CapacityResponse normal = capacityService.getStoreCapacity(storeId, now);
        assertThat(normal.throttleStatus()).isEqualTo("NORMAL");
        assertThat(normal.canAcceptOrders()).isTrue();
        assertThat(normal.etaExtensionMinutes()).isZero();

        normalStore.setCurrentPendingOrders(90);
        CapacityResponse throttled = capacityService.getStoreCapacity(storeId, now);
        assertThat(throttled.throttleStatus()).isEqualTo("THROTTLED");
        assertThat(throttled.canAcceptOrders()).isTrue();
        assertThat(throttled.etaExtensionMinutes()).isEqualTo(15);

        normalStore.setCurrentPendingOrders(100);
        CapacityResponse paused = capacityService.getStoreCapacity(storeId, now);
        assertThat(paused.throttleStatus()).isEqualTo("PAUSED");
        assertThat(paused.canAcceptOrders()).isFalse();
        assertThat(paused.etaExtensionMinutes()).isEqualTo(15);
    }

    @Test
    @DisplayName("acquireOrderSlot emits throttled event at 90% capacity and increments counts")
    void acquireOrderSlotPublishesThrottledAndIncrements() {
        UUID storeId = UUID.randomUUID();
        Store store = buildStore(storeId, 100, 10, 8);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
        when(storeRepository.save(any(Store.class))).thenAnswer(inv -> inv.getArgument(0));
        when(capacityRepository.incrementOrderCount(eq(storeId), any(LocalDate.class), anyInt(), eq(100)))
            .thenReturn(1);

        boolean acquired = capacityService.acquireOrderSlot(storeId, LocalDateTime.of(2026, 11, 13, 10, 0));

        assertThat(acquired).isTrue();
        assertThat(store.getCurrentPendingOrders()).isEqualTo(9);
        verify(outboxService).publish(eq("StoreCapacity"), eq(storeId.toString()), eq("StoreCapacityThrottled"), any(Map.class));
        verify(capacityRepository).incrementOrderCount(eq(storeId), any(LocalDate.class), anyInt(), eq(100));
    }

    @Test
    @DisplayName("acquireOrderSlot throws CapacityExceededException and emits StoreCapacityPaused when full")
    void acquireOrderSlotThrowsWhenPaused() {
        UUID storeId = UUID.randomUUID();
        Store store = buildStore(storeId, 100, 10, 10);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));

        assertThatThrownBy(() -> capacityService.acquireOrderSlot(storeId, LocalDateTime.now()))
            .isInstanceOf(CapacityExceededException.class);

        verify(outboxService).publish(eq("StoreCapacity"), eq(storeId.toString()), eq("StoreCapacityPaused"), any(Map.class));
        verify(storeRepository, never()).save(any());
    }

    @Test
    @DisplayName("releaseOrderSlot decrements pending orders only when positive")
    void releaseOrderSlotDecrementsWhenPositive() {
        UUID storeId = UUID.randomUUID();
        Store store = buildStore(storeId, 100, 10, 2);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));

        capacityService.releaseOrderSlot(storeId);
        assertThat(store.getCurrentPendingOrders()).isEqualTo(1);
        verify(storeRepository).save(store);

        store.setCurrentPendingOrders(0);
        capacityService.releaseOrderSlot(storeId);
        verify(storeRepository).save(store);
    }
}
