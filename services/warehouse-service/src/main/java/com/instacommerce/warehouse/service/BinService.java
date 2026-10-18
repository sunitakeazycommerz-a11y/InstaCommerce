package com.instacommerce.warehouse.service;

import com.instacommerce.warehouse.domain.model.Store;
import com.instacommerce.warehouse.domain.model.WarehouseBin;
import com.instacommerce.warehouse.dto.request.BinAssignmentRequest;
import com.instacommerce.warehouse.dto.response.BinResponse;
import com.instacommerce.warehouse.exception.BinAlreadyOccupiedException;
import com.instacommerce.warehouse.exception.StoreNotFoundException;
import com.instacommerce.warehouse.repository.StoreRepository;
import com.instacommerce.warehouse.repository.WarehouseBinRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BinService {

    private static final Logger log = LoggerFactory.getLogger(BinService.class);

    private final WarehouseBinRepository binRepository;
    private final StoreRepository storeRepository;
    private final OutboxService outboxService;

    public BinService(WarehouseBinRepository binRepository,
                      StoreRepository storeRepository,
                      OutboxService outboxService) {
        this.binRepository = binRepository;
        this.storeRepository = storeRepository;
        this.outboxService = outboxService;
    }

    @Transactional
    public BinResponse assignItem(UUID storeId, BinAssignmentRequest request) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));

        try {
            Optional<WarehouseBin> existing = binRepository.findWithLockByStoreIdAndAisleAndRackAndShelfAndBinNumber(
                    storeId, request.aisle(), request.rack(), request.shelf(), request.binNumber());

            WarehouseBin bin;
            if (existing.isPresent()) {
                bin = existing.get();
                if (bin.isOccupied() && bin.getSku() != null && !bin.getSku().equals(request.sku())) {
                    throw new BinAlreadyOccupiedException(
                            String.format("Bin %s-%s-%s-%s is already occupied by SKU %s",
                                    request.aisle(), request.rack(), request.shelf(), request.binNumber(), bin.getSku()));
                }

                int addedVol = request.volumeCm3() != null ? request.volumeCm3() : 0;
                if (bin.getCurrentVolumeCm3() + addedVol > bin.getMaxVolumeCm3()) {
                    throw new BinAlreadyOccupiedException(
                            String.format("Bin %s-%s-%s-%s volume capacity exceeded. Max: %d, Current: %d, Requested: %d",
                                    request.aisle(), request.rack(), request.shelf(), request.binNumber(),
                                    bin.getMaxVolumeCm3(), bin.getCurrentVolumeCm3(), addedVol));
                }

                bin.setSku(request.sku());
                bin.setCurrentVolumeCm3(bin.getCurrentVolumeCm3() + addedVol);
                if (request.weightGrams() != null) {
                    bin.setCurrentWeightGrams(bin.getCurrentWeightGrams() + request.weightGrams());
                }
                bin.setOccupied(true);
            } else {
                bin = new WarehouseBin();
                bin.setStore(store);
                bin.setAisle(request.aisle());
                bin.setRack(request.rack());
                bin.setShelf(request.shelf());
                bin.setBinNumber(request.binNumber());
                bin.setSku(request.sku());
                int vol = request.volumeCm3() != null ? request.volumeCm3() : 0;
                if (vol > bin.getMaxVolumeCm3()) {
                    throw new BinAlreadyOccupiedException("Requested item volume exceeds max bin capacity");
                }
                bin.setCurrentVolumeCm3(vol);
                bin.setCurrentWeightGrams(request.weightGrams() != null ? request.weightGrams() : 0);
                bin.setOccupied(true);
            }

            WarehouseBin saved = binRepository.saveAndFlush(bin);
            log.info("Assigned SKU {} to bin {}-{}-{}-{} in store {}",
                    request.sku(), request.aisle(), request.rack(), request.shelf(), request.binNumber(), storeId);

            outboxService.publish("WarehouseBin", saved.getId().toString(), "BinAssigned",
                    toResponse(saved));

            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Concurrent duplicate bin assignment detected for store {} location {}-{}-{}-{}",
                    storeId, request.aisle(), request.rack(), request.shelf(), request.binNumber(), ex);
            throw new BinAlreadyOccupiedException(
                    String.format("Duplicate bin assignment detected for location %s-%s-%s-%s in store %s",
                            request.aisle(), request.rack(), request.shelf(), request.binNumber(), storeId));
        }
    }

    @Transactional(readOnly = true)
    public List<BinResponse> getBinsForStore(UUID storeId) {
        return binRepository.findByStoreId(storeId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BinResponse> findBinsBySku(UUID storeId, String sku) {
        return binRepository.findByStoreIdAndSku(storeId, sku).stream()
                .map(this::toResponse)
                .toList();
    }

    private BinResponse toResponse(WarehouseBin bin) {
        return new BinResponse(
                bin.getId(),
                bin.getStore().getId(),
                bin.getAisle(),
                bin.getRack(),
                bin.getShelf(),
                bin.getBinNumber(),
                bin.getSku(),
                bin.getMaxVolumeCm3(),
                bin.getCurrentVolumeCm3(),
                bin.getMaxWeightGrams(),
                bin.getCurrentWeightGrams(),
                bin.isOccupied(),
                bin.getUpdatedAt()
        );
    }
}
