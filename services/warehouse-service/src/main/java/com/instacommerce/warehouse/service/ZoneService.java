package com.instacommerce.warehouse.service;

import com.instacommerce.warehouse.domain.model.StoreZone;
import com.instacommerce.warehouse.dto.response.StoreResponse;
import com.instacommerce.warehouse.repository.StoreZoneRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ZoneService {

    private final StoreZoneRepository storeZoneRepository;

    public ZoneService(StoreZoneRepository storeZoneRepository) {
        this.storeZoneRepository = storeZoneRepository;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "store-zones", key = "#pincode")
    public List<UUID> mapPincodeToStoreIds(String pincode) {
        return storeZoneRepository.findActiveZonesByPincode(pincode).stream()
                .map(zone -> zone.getStore().getId())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StoreResponse.ZoneResponse> getStoreZones(UUID storeId) {
        return storeZoneRepository.findByStoreId(storeId).stream()
                .map(zone -> new StoreResponse.ZoneResponse(
                        zone.getId(),
                        zone.getZoneName(),
                        zone.getPincode(),
                        zone.getDeliveryRadiusKm()))
                .toList();
    }

    @Transactional(readOnly = true)
    public BigDecimal getDeliveryRadius(UUID storeId) {
        return storeZoneRepository.findByStoreId(storeId).stream()
                .map(StoreZone::getDeliveryRadiusKm)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }
}
