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
import com.instacommerce.warehouse.domain.model.StoreBlackoutWindow;
import com.instacommerce.warehouse.domain.model.StoreHours;
import com.instacommerce.warehouse.domain.model.StoreStatus;
import com.instacommerce.warehouse.domain.model.StoreZone;
import com.instacommerce.warehouse.dto.request.CreateBlackoutWindowRequest;
import com.instacommerce.warehouse.dto.request.SurgeModeRequest;
import com.instacommerce.warehouse.dto.response.BlackoutWindowResponse;
import com.instacommerce.warehouse.dto.response.CapacityResponse;
import com.instacommerce.warehouse.dto.response.ZoneResolutionResponse;
import com.instacommerce.warehouse.exception.StoreClosedException;
import com.instacommerce.warehouse.repository.StoreBlackoutWindowRepository;
import com.instacommerce.warehouse.repository.StoreCapacityRepository;
import com.instacommerce.warehouse.repository.StoreHoursRepository;
import com.instacommerce.warehouse.repository.StoreRepository;
import com.instacommerce.warehouse.repository.StoreZoneRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreBlackoutSurgeAndFallbackTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private StoreHoursRepository storeHoursRepository;

    @Mock
    private StoreCapacityRepository capacityRepository;

    @Mock
    private StoreZoneRepository storeZoneRepository;

    @Mock
    private StoreBlackoutWindowRepository blackoutWindowRepository;

    @Mock
    private OutboxService outboxService;

    private Clock fixedClock;
    private StoreService storeService;
    private CapacityService capacityService;
    private ZoneService zoneService;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        storeService = new StoreService(
            storeRepository,
            storeHoursRepository,
            outboxService,
            new WarehouseProperties(),
            fixedClock,
            blackoutWindowRepository
        );
        capacityService = new CapacityService(
            capacityRepository,
            storeRepository,
            outboxService,
            new CapacityAlertPublisher(outboxService),
            blackoutWindowRepository,
            fixedClock
        );
        zoneService = new ZoneService(storeZoneRepository, storeRepository, storeService, capacityService);
    }

    @Test
    @DisplayName("isStoreOpen returns false during an active blackout window and validates window creation")
    void blackoutWindowClosesStoreEvenWithinOperatingHours() {
        UUID storeId = UUID.randomUUID();
        Store store = buildStore(storeId, "Koramangala Store", StoreStatus.ACTIVE, 100, 0);

        StoreHours openHours = new StoreHours();
        openHours.setStore(store);
        openHours.setDayOfWeek(4);
        openHours.setOpensAt(LocalTime.of(0, 0));
        openHours.setClosesAt(LocalTime.of(23, 59));
        openHours.setHoliday(false);

        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
        when(blackoutWindowRepository.existsActiveBlackout(storeId, NOW)).thenReturn(true);

        assertThat(storeService.isStoreOpen(storeId, NOW)).isFalse();

        when(blackoutWindowRepository.existsActiveBlackout(storeId, NOW)).thenReturn(false);
        when(storeHoursRepository.findByStoreIdAndDayOfWeek(storeId, 4)).thenReturn(Optional.of(openHours));
        assertThat(storeService.isStoreOpen(storeId, NOW)).isTrue();

        assertThatThrownBy(() -> storeService.createBlackoutWindow(
            storeId,
            new CreateBlackoutWindowRequest(NOW.plusSeconds(60), NOW, "invalid")
        )).isInstanceOf(IllegalArgumentException.class);

        when(blackoutWindowRepository.save(any(StoreBlackoutWindow.class))).thenAnswer(inv -> {
            StoreBlackoutWindow w = inv.getArgument(0);
            w.setId(UUID.randomUUID());
            return w;
        });
        BlackoutWindowResponse created = storeService.createBlackoutWindow(
            storeId,
            new CreateBlackoutWindowRequest(NOW, NOW.plus(Duration.ofHours(2)), "Freezer maintenance")
        );
        assertThat(created.reason()).isEqualTo("Freezer maintenance");
        verify(outboxService).publish(eq("Store"), eq(storeId.toString()), eq("StoreBlackoutScheduled"), any(Map.class));
    }

    @Test
    @DisplayName("Surge mode reports SURGE status with ETA extension and acquireOrderSlot rejects blacked-out store")
    void surgeModeAndBlackoutGuardsCapacity() {
        UUID storeId = UUID.randomUUID();
        Store store = buildStore(storeId, "Indiranagar Store", StoreStatus.ACTIVE, 100, 10);

        when(storeRepository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store));
        when(storeRepository.save(any(Store.class))).thenAnswer(inv -> inv.getArgument(0));
        when(blackoutWindowRepository.existsActiveBlackout(eq(storeId), any(Instant.class))).thenReturn(false);

        CapacityResponse surge = capacityService.updateSurgeMode(storeId, new SurgeModeRequest(true, 20, 85));
        assertThat(surge.throttleStatus()).isEqualTo("SURGE");
        assertThat(surge.etaExtensionMinutes()).isEqualTo(20);
        assertThat(surge.canAcceptOrders()).isTrue();
        verify(outboxService).publish(eq("StoreCapacity"), eq(storeId.toString()), eq("StoreSurgeModeChanged"), any(Map.class));

        when(capacityRepository.incrementOrderCount(eq(storeId), any(LocalDate.class), anyInt(), eq(200)))
            .thenReturn(1);
        assertThat(capacityService.acquireOrderSlot(storeId, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))).isTrue();
        verify(outboxService).publish(eq("StoreCapacity"), eq(storeId.toString()), eq("StoreCapacityThrottled"), any(Map.class));

        when(blackoutWindowRepository.existsActiveBlackout(eq(storeId), any(Instant.class))).thenReturn(true);
        assertThatThrownBy(() -> capacityService.acquireOrderSlot(storeId, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)))
            .isInstanceOf(StoreClosedException.class);
    }

    @Test
    @DisplayName("resolveStoresForOrder prioritizes open primary zone and falls back to nearest eligible store when full")
    void resolveStoresFallsBackWhenPrimaryIsFullOrBlackedOut() {
        UUID fullPrimaryId = UUID.randomUUID();
        UUID fallbackId = UUID.randomUUID();

        Store fullPrimary = buildStore(fullPrimaryId, "HSR Full", StoreStatus.ACTIVE, 20, 20);
        Store fallbackStore = buildStore(fallbackId, "BTM Fallback", StoreStatus.ACTIVE, 100, 10);

        StoreZone primaryZone = new StoreZone();
        primaryZone.setStore(fullPrimary);
        primaryZone.setZoneName("HSR-1");
        primaryZone.setPincode("560102");
        primaryZone.setDeliveryRadiusKm(new BigDecimal("4.00"));

        StoreHours alwaysOpen = new StoreHours();
        alwaysOpen.setDayOfWeek(4);
        alwaysOpen.setOpensAt(LocalTime.of(0, 0));
        alwaysOpen.setClosesAt(LocalTime.of(0, 0));
        alwaysOpen.setHoliday(false);

        when(storeZoneRepository.findActiveZonesByPincode("560102")).thenReturn(List.of(primaryZone));
        when(storeRepository.findById(fullPrimaryId)).thenReturn(Optional.of(fullPrimary));
        when(storeRepository.findById(fallbackId)).thenReturn(Optional.of(fallbackStore));
        when(blackoutWindowRepository.existsActiveBlackout(eq(fullPrimaryId), any(Instant.class))).thenReturn(false);
        when(blackoutWindowRepository.existsActiveBlackout(eq(fallbackId), any(Instant.class))).thenReturn(false);
        when(storeHoursRepository.findByStoreIdAndDayOfWeek(fullPrimaryId, 4)).thenReturn(Optional.of(alwaysOpen));
        when(storeHoursRepository.findByStoreIdAndDayOfWeek(fallbackId, 4)).thenReturn(Optional.of(alwaysOpen));
        when(storeRepository.findNearestStores(12.91, 77.64, 15.0, 10)).thenReturn(List.of(fullPrimary, fallbackStore));

        List<ZoneResolutionResponse> resolved = zoneService.resolveStoresForOrder("560102", 12.91, 77.64);
        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).storeId()).isEqualTo(fallbackId);
        assertThat(resolved.get(0).resolutionSource()).isEqualTo("FALLBACK_NEAREST");
        assertThat(resolved.get(0).canAcceptOrders()).isTrue();
    }

    private Store buildStore(UUID id, String name, StoreStatus status, int maxConcurrent, int pending) {
        Store store = new Store();
        store.setId(id);
        store.setName(name);
        store.setAddress("Main St");
        store.setCity("Bengaluru");
        store.setState("KA");
        store.setPincode("560102");
        store.setLatitude(new BigDecimal("12.91"));
        store.setLongitude(new BigDecimal("77.64"));
        store.setTimezone("UTC");
        store.setStatus(status);
        store.setCapacityOrdersPerHour(200);
        store.setMaxConcurrentOrders(maxConcurrent);
        store.setCurrentPendingOrders(pending);
        return store;
    }
}
