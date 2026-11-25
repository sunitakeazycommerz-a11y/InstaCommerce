package com.instacommerce.warehouse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.warehouse.config.CacheConfig;
import com.instacommerce.warehouse.config.WarehouseProperties;
import com.instacommerce.warehouse.domain.model.Store;
import com.instacommerce.warehouse.domain.model.StoreHours;
import com.instacommerce.warehouse.domain.model.StoreStatus;
import com.instacommerce.warehouse.dto.request.UpsertStoreHoursRequest;
import com.instacommerce.warehouse.repository.StoreBlackoutWindowRepository;
import com.instacommerce.warehouse.repository.StoreHoursRepository;
import com.instacommerce.warehouse.repository.StoreRepository;
import com.instacommerce.warehouse.repository.StoreZoneRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(classes = StoreHoursCacheTest.TestConfig.class)
class StoreHoursCacheTest {

    @Configuration
    @Import({CacheConfig.class, StoreService.class, WarehouseProperties.class})
    static class TestConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @MockitoBean private StoreRepository storeRepository;
    @MockitoBean private StoreHoursRepository storeHoursRepository;
    @MockitoBean private OutboxService outboxService;
    @MockitoBean private StoreBlackoutWindowRepository blackoutWindowRepository;
    @MockitoBean private StoreZoneRepository storeZoneRepository;

    @Autowired private StoreService storeService;
    @Autowired private CacheManager cacheManager;

    @BeforeEach
    void clearCaches() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    @DisplayName("checkStoreHours caches hours in store-hours Caffeine cache and upsertStoreHours evicts it")
    void storeHoursAreCachedAndEvictedOnUpsert() {
        UUID storeId = UUID.randomUUID();
        Store store = new Store();
        store.setId(storeId);
        store.setName("Indiranagar Hub");
        store.setLatitude(new BigDecimal("12.9784"));
        store.setLongitude(new BigDecimal("77.6408"));
        store.setTimezone("Asia/Kolkata");
        store.setStatus(StoreStatus.ACTIVE);

        StoreHours fridayHours = new StoreHours();
        fridayHours.setId(UUID.randomUUID());
        fridayHours.setStore(store);
        fridayHours.setDayOfWeek(5);
        fridayHours.setOpensAt(LocalTime.of(6, 0));
        fridayHours.setClosesAt(LocalTime.of(23, 30));
        fridayHours.setHoliday(false);

        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
        when(storeHoursRepository.findByStoreIdAndDayOfWeek(storeId, 5)).thenReturn(Optional.of(fridayHours));
        when(storeHoursRepository.save(any(StoreHours.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant fridayEvening = ZonedDateTime.of(2026, 11, 13, 21, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant();

        assertThat(storeService.isStoreOpen(storeId, fridayEvening)).isTrue();
        assertThat(storeService.isStoreOpen(storeId, fridayEvening)).isTrue();
        verify(storeHoursRepository, times(1)).findByStoreIdAndDayOfWeek(storeId, 5);

        storeService.upsertStoreHours(storeId, new UpsertStoreHoursRequest(5, LocalTime.of(8, 0), LocalTime.of(20, 0), false));
        verify(storeHoursRepository, times(2)).findByStoreIdAndDayOfWeek(storeId, 5);

        assertThat(storeService.isStoreOpen(storeId, fridayEvening)).isFalse();
        assertThat(storeService.isStoreOpen(storeId, fridayEvening)).isFalse();
        verify(storeHoursRepository, times(3)).findByStoreIdAndDayOfWeek(storeId, 5);
    }
}
