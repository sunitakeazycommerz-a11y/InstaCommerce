package com.instacommerce.warehouse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.warehouse.domain.model.Store;
import com.instacommerce.warehouse.domain.model.StoreZone;
import com.instacommerce.warehouse.dto.request.CreateZoneRequest;
import com.instacommerce.warehouse.dto.request.UpdateZoneRequest;
import com.instacommerce.warehouse.dto.response.StoreResponse;
import com.instacommerce.warehouse.exception.StoreNotFoundException;
import com.instacommerce.warehouse.exception.ZoneAlreadyExistsException;
import com.instacommerce.warehouse.exception.ZoneNotFoundException;
import com.instacommerce.warehouse.repository.StoreRepository;
import com.instacommerce.warehouse.repository.StoreZoneRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ZoneServiceTest {

    @Mock
    private StoreZoneRepository storeZoneRepository;

    @Mock
    private StoreRepository storeRepository;

    @InjectMocks
    private ZoneService zoneService;

    private Store buildStore(UUID storeId) {
        Store store = new Store();
        store.setId(storeId);
        store.setName("HSR Hub");
        return store;
    }

    private StoreZone buildZone(UUID zoneId, Store store, String name, String pincode, BigDecimal radiusKm) {
        StoreZone zone = new StoreZone();
        zone.setId(zoneId);
        zone.setStore(store);
        zone.setZoneName(name);
        zone.setPincode(pincode);
        zone.setDeliveryRadiusKm(radiusKm);
        return zone;
    }

    @Test
    @DisplayName("createZone persists zone with default 5.00 km radius when omitted")
    void createZonePersistsNewZone() {
        UUID storeId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        Store store = buildStore(storeId);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
        when(storeZoneRepository.findByStoreIdAndPincode(storeId, "560102")).thenReturn(Optional.empty());
        when(storeZoneRepository.save(any(StoreZone.class))).thenAnswer(inv -> {
            StoreZone z = inv.getArgument(0);
            z.setId(zoneId);
            return z;
        });

        StoreResponse.ZoneResponse created = zoneService.createZone(
            storeId,
            new CreateZoneRequest(" HSR Sector 2 ", " 560102 ", null)
        );

        assertThat(created.id()).isEqualTo(zoneId);
        assertThat(created.zoneName()).isEqualTo("HSR Sector 2");
        assertThat(created.pincode()).isEqualTo("560102");
        assertThat(created.deliveryRadiusKm()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("createZone rejects duplicate pincode for same store and missing store")
    void createZoneRejectsMissingStoreAndDuplicatePincode() {
        UUID missingStoreId = UUID.randomUUID();
        when(storeRepository.findById(missingStoreId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> zoneService.createZone(
            missingStoreId,
            new CreateZoneRequest("Zone A", "560001", new BigDecimal("4.00"))
        )).isInstanceOf(StoreNotFoundException.class);

        UUID storeId = UUID.randomUUID();
        Store store = buildStore(storeId);
        StoreZone existing = buildZone(UUID.randomUUID(), store, "Existing", "560001", new BigDecimal("3.00"));
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
        when(storeZoneRepository.findByStoreIdAndPincode(storeId, "560001")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> zoneService.createZone(
            storeId,
            new CreateZoneRequest("Dup", "560001", new BigDecimal("3.50"))
        )).isInstanceOf(ZoneAlreadyExistsException.class);
        verify(storeZoneRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateZone modifies fields, enforces pincode uniqueness, and throws ZoneNotFoundException when absent")
    void updateZoneMutatesAndValidates() {
        UUID storeId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        Store store = buildStore(storeId);
        StoreZone zone = buildZone(zoneId, store, "Old Name", "560001", new BigDecimal("3.00"));

        when(storeZoneRepository.findByIdAndStoreId(zoneId, storeId)).thenReturn(Optional.of(zone));
        when(storeZoneRepository.findByStoreIdAndPincode(storeId, "560002")).thenReturn(Optional.empty());
        when(storeZoneRepository.save(any(StoreZone.class))).thenAnswer(inv -> inv.getArgument(0));

        StoreResponse.ZoneResponse updated = zoneService.updateZone(
            storeId,
            zoneId,
            new UpdateZoneRequest("New Name", "560002", new BigDecimal("6.50"))
        );

        assertThat(updated.zoneName()).isEqualTo("New Name");
        assertThat(updated.pincode()).isEqualTo("560002");
        assertThat(updated.deliveryRadiusKm()).isEqualByComparingTo("6.50");

        StoreZone conflicting = buildZone(UUID.randomUUID(), store, "Other", "560099", new BigDecimal("5.00"));
        when(storeZoneRepository.findByStoreIdAndPincode(storeId, "560099")).thenReturn(Optional.of(conflicting));

        assertThatThrownBy(() -> zoneService.updateZone(
            storeId,
            zoneId,
            new UpdateZoneRequest(null, "560099", null)
        )).isInstanceOf(ZoneAlreadyExistsException.class);

        UUID unknownZoneId = UUID.randomUUID();
        when(storeZoneRepository.findByIdAndStoreId(unknownZoneId, storeId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> zoneService.updateZone(
            storeId,
            unknownZoneId,
            new UpdateZoneRequest("X", null, null)
        )).isInstanceOf(ZoneNotFoundException.class);
    }

    @Test
    @DisplayName("deleteZone removes zone belonging to store or throws ZoneNotFoundException")
    void deleteZoneDeletesOrThrowsNotFound() {
        UUID storeId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        StoreZone zone = buildZone(zoneId, buildStore(storeId), "Zone", "560001", new BigDecimal("5.00"));
        when(storeZoneRepository.findByIdAndStoreId(zoneId, storeId)).thenReturn(Optional.of(zone));

        zoneService.deleteZone(storeId, zoneId);
        ArgumentCaptor<StoreZone> deletedCaptor = ArgumentCaptor.forClass(StoreZone.class);
        verify(storeZoneRepository).delete(deletedCaptor.capture());
        assertThat(deletedCaptor.getValue().getId()).isEqualTo(zoneId);

        UUID missingZoneId = UUID.randomUUID();
        when(storeZoneRepository.findByIdAndStoreId(missingZoneId, storeId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> zoneService.deleteZone(storeId, missingZoneId))
            .isInstanceOf(ZoneNotFoundException.class);
    }

    @Test
    @DisplayName("getDeliveryRadius returns maximum zone radius or zero when store has no zones")
    void getDeliveryRadiusComputesMaximum() {
        UUID storeId = UUID.randomUUID();
        Store store = buildStore(storeId);
        when(storeZoneRepository.findByStoreId(storeId)).thenReturn(List.of(
            buildZone(UUID.randomUUID(), store, "Z1", "560001", new BigDecimal("3.50")),
            buildZone(UUID.randomUUID(), store, "Z2", "560002", new BigDecimal("8.25"))
        ));

        assertThat(zoneService.getDeliveryRadius(storeId)).isEqualByComparingTo("8.25");

        UUID emptyStoreId = UUID.randomUUID();
        when(storeZoneRepository.findByStoreId(emptyStoreId)).thenReturn(List.of());
        assertThat(zoneService.getDeliveryRadius(emptyStoreId)).isEqualByComparingTo("0");
    }
}
