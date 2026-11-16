package com.instacommerce.warehouse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.warehouse.config.WarehouseProperties;
import com.instacommerce.warehouse.domain.model.Store;
import com.instacommerce.warehouse.domain.model.StoreHours;
import com.instacommerce.warehouse.domain.model.StoreStatus;
import com.instacommerce.warehouse.domain.model.WarehouseBin;
import com.instacommerce.warehouse.dto.request.BinAssignmentRequest;
import com.instacommerce.warehouse.dto.request.UpsertStoreHoursRequest;
import com.instacommerce.warehouse.dto.response.StoreResponse;
import com.instacommerce.warehouse.exception.BinAlreadyOccupiedException;
import com.instacommerce.warehouse.exception.CapacityExceededException;
import com.instacommerce.warehouse.repository.StoreCapacityRepository;
import com.instacommerce.warehouse.repository.StoreHoursRepository;
import com.instacommerce.warehouse.repository.StoreRepository;
import com.instacommerce.warehouse.repository.WarehouseBinRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WarehouseRemediationTest {

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private StoreHoursRepository storeHoursRepository;

    @Mock
    private StoreCapacityRepository capacityRepository;

    @Mock
    private WarehouseBinRepository binRepository;

    @Mock
    private OutboxService outboxService;

    @Mock
    private CapacityAlertPublisher capacityAlertPublisher;

    @Test
    @DisplayName("StoreService uses injected Clock in store zone when LocalDateTime is null and upserts StoreHours")
    void storeServiceClockTimezoneAndHoursUpsert() {
        UUID storeId = UUID.randomUUID();
        Store store = new Store();
        store.setId(storeId);
        store.setStatus(StoreStatus.ACTIVE);
        store.setTimezone("Asia/Kolkata");

        // 2026-11-16T01:00:00Z = 06:30 Asia/Kolkata (Monday = dayOfWeek 1)
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-16T01:00:00Z"), ZoneId.of("UTC"));
        StoreService storeService = new StoreService(
            storeRepository, storeHoursRepository, outboxService, new WarehouseProperties(), fixedClock);

        StoreHours hours = new StoreHours();
        hours.setId(UUID.randomUUID());
        hours.setStore(store);
        hours.setDayOfWeek(1);
        hours.setOpensAt(LocalTime.of(6, 0));
        hours.setClosesAt(LocalTime.of(22, 0));
        hours.setHoliday(false);

        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
        when(storeHoursRepository.findByStoreIdAndDayOfWeek(storeId, 1)).thenReturn(Optional.of(hours));
        when(storeHoursRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(storeService.isStoreOpen(storeId, (LocalDateTime) null)).isTrue();

        StoreResponse.HoursResponse updated = storeService.upsertStoreHours(
            storeId, new UpsertStoreHoursRequest(1, LocalTime.of(7, 0), LocalTime.of(23, 0), false));
        assertThat(updated.opensAt()).isEqualTo("07:00");
        verify(outboxService).publish(eq("Store"), eq(storeId.toString()), eq("StoreHoursUpdated"), any(Map.class));
    }

    @Test
    @DisplayName("CapacityService publishes StoreCapacityPaused via REQUIRES_NEW helper when hourly cap exceeded")
    void capacityServicePublishesPausedViaNewTxOnHourlyOverflow() {
        UUID storeId = UUID.randomUUID();
        Store store = new Store();
        store.setId(storeId);
        store.setTimezone("UTC");
        store.setCapacityOrdersPerHour(10);
        store.setMaxConcurrentOrders(100);
        store.setCurrentPendingOrders(5);

        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
        when(capacityRepository.incrementOrderCount(eq(storeId), any(LocalDate.class), anyInt(), eq(10)))
            .thenReturn(0);

        CapacityService service = new CapacityService(
            capacityRepository, storeRepository, outboxService, capacityAlertPublisher);

        assertThatThrownBy(() -> service.acquireOrderSlot(storeId, LocalDateTime.of(2026, 11, 16, 12, 0)))
            .isInstanceOf(CapacityExceededException.class);
        verify(capacityAlertPublisher).publishCapacityPaused(eq(storeId.toString()), any());
    }

    @Test
    @DisplayName("BinService enforces maxWeightGrams on both existing and new bin assignments")
    void binServiceEnforcesWeightLimit() {
        UUID storeId = UUID.randomUUID();
        Store store = new Store();
        store.setId(storeId);

        WarehouseBin bin = new WarehouseBin();
        bin.setStore(store);
        bin.setAisle("A1");
        bin.setRack("R1");
        bin.setShelf("S1");
        bin.setBinNumber("B1");
        bin.setSku("SKU-1");
        bin.setMaxWeightGrams(25_000);
        bin.setCurrentWeightGrams(24_000);
        bin.setOccupied(true);

        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
        when(binRepository.findWithLockByStoreIdAndAisleAndRackAndShelfAndBinNumber(
            storeId, "A1", "R1", "S1", "B1")).thenReturn(Optional.of(bin));

        BinService binService = new BinService(binRepository, storeRepository, outboxService);
        BinAssignmentRequest heavyExisting = new BinAssignmentRequest("A1", "R1", "S1", "B1", "SKU-1", 100, 1500);
        assertThatThrownBy(() -> binService.assignItem(storeId, heavyExisting))
            .isInstanceOf(BinAlreadyOccupiedException.class)
            .hasMessageContaining("weight capacity exceeded");

        when(binRepository.findWithLockByStoreIdAndAisleAndRackAndShelfAndBinNumber(
            storeId, "A2", "R1", "S1", "B1")).thenReturn(Optional.empty());
        BinAssignmentRequest heavyNew = new BinAssignmentRequest("A2", "R1", "S1", "B1", "SKU-2", 100, 30_000);
        assertThatThrownBy(() -> binService.assignItem(storeId, heavyNew))
            .isInstanceOf(BinAlreadyOccupiedException.class)
            .hasMessageContaining("weight");
    }
}
