package com.instacommerce.warehouse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.warehouse.config.CacheConfig;
import com.instacommerce.warehouse.domain.model.Store;
import com.instacommerce.warehouse.domain.model.StoreZone;
import com.instacommerce.warehouse.dto.request.CreateZoneRequest;
import com.instacommerce.warehouse.dto.request.UpdateZoneRequest;
import com.instacommerce.warehouse.repository.StoreRepository;
import com.instacommerce.warehouse.repository.StoreZoneRepository;
import java.math.BigDecimal;
import java.util.List;
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

@SpringJUnitConfig(classes = ZoneCacheEvictionTest.TestConfig.class)
class ZoneCacheEvictionTest {

    @Configuration
    @Import(CacheConfig.class)
    static class TestConfig {
        @Bean
        ZoneService zoneService(StoreZoneRepository storeZoneRepository, StoreRepository storeRepository) {
            return new ZoneService(storeZoneRepository, storeRepository);
        }
    }

    @MockitoBean
    private StoreZoneRepository storeZoneRepository;

    @MockitoBean
    private StoreRepository storeRepository;

    @Autowired
    private ZoneService zoneService;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void clearCaches() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    @DisplayName("createZone, updateZone, and deleteZone evict store-zones cache so pincode lookups refresh")
    void zoneMutationsEvictStoreZonesCache() {
        UUID storeId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        Store store = new Store();
        store.setId(storeId);
        StoreZone zone = new StoreZone();
        zone.setId(zoneId);
        zone.setStore(store);
        zone.setZoneName("Central");
        zone.setPincode("560001");
        zone.setDeliveryRadiusKm(new BigDecimal("5.00"));

        when(storeZoneRepository.findActiveZonesByPincode("560001")).thenReturn(List.of(zone));
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
        when(storeZoneRepository.findByStoreIdAndPincode(storeId, "560002")).thenReturn(Optional.empty());
        when(storeZoneRepository.save(any(StoreZone.class))).thenReturn(zone);
        when(storeZoneRepository.findByIdAndStoreId(zoneId, storeId)).thenReturn(Optional.of(zone));
        when(storeZoneRepository.findByStoreIdAndPincode(storeId, "560003")).thenReturn(Optional.empty());

        assertThat(zoneService.mapPincodeToStoreIds("560001")).containsExactly(storeId);
        assertThat(zoneService.mapPincodeToStoreIds("560001")).containsExactly(storeId);
        verify(storeZoneRepository, times(1)).findActiveZonesByPincode("560001");

        zoneService.createZone(storeId, new CreateZoneRequest("North", "560002", new BigDecimal("4.00")));
        assertThat(zoneService.mapPincodeToStoreIds("560001")).containsExactly(storeId);
        verify(storeZoneRepository, times(2)).findActiveZonesByPincode("560001");

        zoneService.updateZone(storeId, zoneId, new UpdateZoneRequest("Central Updated", "560003", new BigDecimal("6.00")));
        assertThat(zoneService.mapPincodeToStoreIds("560001")).containsExactly(storeId);
        verify(storeZoneRepository, times(3)).findActiveZonesByPincode("560001");

        zoneService.deleteZone(storeId, zoneId);
        assertThat(zoneService.mapPincodeToStoreIds("560001")).containsExactly(storeId);
        verify(storeZoneRepository, times(4)).findActiveZonesByPincode("560001");
    }
}
