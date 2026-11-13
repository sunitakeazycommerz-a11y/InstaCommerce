package com.instacommerce.warehouse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.instacommerce.warehouse.config.WarehouseProperties;
import com.instacommerce.warehouse.domain.model.Store;
import com.instacommerce.warehouse.domain.model.StoreHours;
import com.instacommerce.warehouse.domain.model.StoreStatus;
import com.instacommerce.warehouse.dto.request.CreateStoreRequest;
import com.instacommerce.warehouse.dto.response.StoreResponse;
import com.instacommerce.warehouse.exception.StoreNotFoundException;
import com.instacommerce.warehouse.repository.StoreHoursRepository;
import com.instacommerce.warehouse.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
class StoreServiceTest {

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private StoreHoursRepository storeHoursRepository;

    @Mock
    private OutboxService outboxService;

    private StoreService storeService;

    @BeforeEach
    void setUp() {
        storeService = new StoreService(storeRepository, storeHoursRepository, outboxService, new WarehouseProperties());
    }

    private Store buildStore(UUID id, StoreStatus status, String timezone) {
        Store store = new Store();
        store.setId(id);
        store.setName("Koramangala Dark Store");
        store.setAddress("100 Feet Road");
        store.setCity("Bengaluru");
        store.setState("KA");
        store.setPincode("560034");
        store.setLatitude(new BigDecimal("12.93520000"));
        store.setLongitude(new BigDecimal("77.62450000"));
        store.setTimezone(timezone);
        store.setStatus(status);
        store.setCapacityOrdersPerHour(120);
        return store;
    }

    @Test
    @DisplayName("getStore returns StoreResponse when store exists and throws StoreNotFoundException otherwise")
    void getStoreReturnsOrThrowsNotFound() {
        UUID storeId = UUID.randomUUID();
        Store store = buildStore(storeId, StoreStatus.ACTIVE, "Asia/Kolkata");
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));

        StoreResponse response = storeService.getStore(storeId);
        assertThat(response.id()).isEqualTo(storeId);
        assertThat(response.city()).isEqualTo("Bengaluru");

        UUID missingId = UUID.randomUUID();
        when(storeRepository.findById(missingId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> storeService.getStore(missingId))
            .isInstanceOf(StoreNotFoundException.class);
    }

    @Test
    @DisplayName("isStoreOpen returns false immediately when store status is not ACTIVE")
    void isStoreOpenReturnsFalseForInactiveStore() {
        UUID storeId = UUID.randomUUID();
        Store store = buildStore(storeId, StoreStatus.TEMPORARILY_CLOSED, "Asia/Kolkata");
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));

        assertThat(storeService.isStoreOpen(storeId, Instant.now())).isFalse();
        verifyNoInteractions(storeHoursRepository);
    }

    @Test
    @DisplayName("isStoreOpen evaluates operating hours in store local timezone and supports overnight windows")
    void isStoreOpenEvaluatesTimezoneAndOvernightWindows() {
        UUID storeId = UUID.randomUUID();
        Store store = buildStore(storeId, StoreStatus.ACTIVE, "Asia/Kolkata");
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));

        Instant fridayEveningIst = ZonedDateTime.of(2026, 11, 13, 23, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant();
        StoreHours fridayHours = new StoreHours();
        fridayHours.setDayOfWeek(5);
        fridayHours.setOpensAt(LocalTime.of(6, 0));
        fridayHours.setClosesAt(LocalTime.of(23, 30));
        fridayHours.setHoliday(false);
        when(storeHoursRepository.findByStoreIdAndDayOfWeek(storeId, 5)).thenReturn(Optional.of(fridayHours));

        assertThat(storeService.isStoreOpen(storeId, fridayEveningIst)).isTrue();

        Instant saturdayEarlyIst = ZonedDateTime.of(2026, 11, 14, 1, 30, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant();
        StoreHours saturdayHoliday = new StoreHours();
        saturdayHoliday.setDayOfWeek(6);
        saturdayHoliday.setOpensAt(LocalTime.of(8, 0));
        saturdayHoliday.setClosesAt(LocalTime.of(20, 0));
        saturdayHoliday.setHoliday(true);

        StoreHours fridayOvernight = new StoreHours();
        fridayOvernight.setDayOfWeek(5);
        fridayOvernight.setOpensAt(LocalTime.of(22, 0));
        fridayOvernight.setClosesAt(LocalTime.of(2, 0));
        fridayOvernight.setHoliday(false);

        when(storeHoursRepository.findByStoreIdAndDayOfWeek(storeId, 6)).thenReturn(Optional.of(saturdayHoliday));
        when(storeHoursRepository.findByStoreIdAndDayOfWeek(storeId, 5)).thenReturn(Optional.of(fridayOvernight));

        assertThat(storeService.isStoreOpen(storeId, saturdayEarlyIst)).isTrue();
    }

    @Test
    @DisplayName("createStore validates timezone and publishes StoreCreated outbox event")
    void createStoreSavesAndPublishesOutbox() {
        CreateStoreRequest badRequest = new CreateStoreRequest(
            "Invalid TZ", "Addr", "City", "State", "560001",
            new BigDecimal("12.9"), new BigDecimal("77.6"), "Not/AZone", 100
        );
        assertThatThrownBy(() -> storeService.createStore(badRequest))
            .isInstanceOf(IllegalArgumentException.class);

        UUID createdId = UUID.randomUUID();
        when(storeRepository.save(any(Store.class))).thenAnswer(inv -> {
            Store s = inv.getArgument(0);
            s.setId(createdId);
            return s;
        });

        CreateStoreRequest request = new CreateStoreRequest(
            "Indiranagar Hub", "12th Main", "Bengaluru", "KA", "560038",
            new BigDecimal("12.9784"), new BigDecimal("77.6408"), "Asia/Kolkata", null
        );
        StoreResponse created = storeService.createStore(request);

        assertThat(created.id()).isEqualTo(createdId);
        assertThat(created.capacityOrdersPerHour()).isEqualTo(100);
        assertThat(created.status()).isEqualTo("ACTIVE");
        verify(outboxService).publish(eq("Store"), eq(createdId.toString()), eq("StoreCreated"), any(Map.class));
    }

    @Test
    @DisplayName("updateStoreStatus transitions status and emits StoreStatusChanged outbox event")
    void updateStoreStatusPublishesOutbox() {
        UUID storeId = UUID.randomUUID();
        Store store = buildStore(storeId, StoreStatus.ACTIVE, "UTC");
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
        when(storeRepository.save(any(Store.class))).thenAnswer(inv -> inv.getArgument(0));

        StoreResponse updated = storeService.updateStoreStatus(storeId, StoreStatus.TEMPORARILY_CLOSED);

        assertThat(updated.status()).isEqualTo("TEMPORARILY_CLOSED");
        verify(outboxService).publish(eq("Store"), eq(storeId.toString()), eq("StoreStatusChanged"), any(Map.class));
    }
}
