package com.instacommerce.warehouse.service;

import com.instacommerce.warehouse.domain.layout.ItemCategory;
import com.instacommerce.warehouse.domain.layout.StoreLayoutGraph;
import com.instacommerce.warehouse.dto.request.OptimizePathRequest;
import com.instacommerce.warehouse.dto.request.PickItemLocationRequest;
import com.instacommerce.warehouse.dto.response.OptimizedPathResponse;
import com.instacommerce.warehouse.dto.response.PickStepResponse;
import com.instacommerce.warehouse.exception.StoreNotFoundException;
import com.instacommerce.warehouse.repository.StoreRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class StoreLayoutService {

    private static final Logger log = LoggerFactory.getLogger(StoreLayoutService.class);

    private final StoreRepository storeRepository;
    private final StoreLayoutGraph layoutGraph;

    public StoreLayoutService(StoreRepository storeRepository) {
        this.storeRepository = storeRepository;
        this.layoutGraph = StoreLayoutGraph.createDefaultDarkStoreFloorplan();
    }

    public OptimizedPathResponse optimizePickPath(UUID storeId, OptimizePathRequest request) {
        if (!storeRepository.existsById(storeId)) {
            throw new StoreNotFoundException(storeId);
        }

        List<PickItemLocationRequest> sortedItems = new ArrayList<>(request.items());
        // Sort primarily by weight order (Heavy goods first -> Fragile last)
        // Secondary sort by aisle to minimize walking back and forth within the same weight tier
        sortedItems.sort(Comparator
                .comparingInt((PickItemLocationRequest item) -> ItemCategory.fromString(item.category()).getWeightOrder())
                .thenComparing(PickItemLocationRequest::aisle));

        String currentLocation = request.startLocation() != null ? request.startLocation() : "ENTRANCE";
        String endLocation = request.endLocation() != null ? request.endLocation() : "PACKING";

        double totalDistance = 0.0;
        List<PickStepResponse> pickSteps = new ArrayList<>();
        Set<String> waypoints = new LinkedHashSet<>();
        waypoints.add(currentLocation);

        int seq = 1;
        for (PickItemLocationRequest item : sortedItems) {
            String targetLocation = item.aisle();
            StoreLayoutGraph.PathResult path = layoutGraph.findShortestPath(currentLocation, targetLocation);

            totalDistance += path.totalDistanceMeters();
            waypoints.addAll(path.nodes());

            ItemCategory category = ItemCategory.fromString(item.category());
            String instruction = String.format("Navigate to Aisle %s, Shelf %s, Bin %s for %s (%s)",
                    item.aisle(),
                    item.shelf() != null ? item.shelf() : "-",
                    item.bin() != null ? item.bin() : "-",
                    item.itemName(),
                    category.getDescription());

            pickSteps.add(new PickStepResponse(
                    seq++,
                    item.sku(),
                    item.itemName(),
                    item.aisle(),
                    item.shelf(),
                    item.bin(),
                    category.name(),
                    item.quantity() != null ? item.quantity() : 1,
                    Math.round(path.totalDistanceMeters() * 100.0) / 100.0,
                    instruction
            ));

            currentLocation = targetLocation;
        }

        // Add traversal from last pick item to packing bay
        StoreLayoutGraph.PathResult returnPath = layoutGraph.findShortestPath(currentLocation, endLocation);
        totalDistance += returnPath.totalDistanceMeters();
        waypoints.addAll(returnPath.nodes());

        // Walking speed ~ 1.2 m/s plus 15s per item pick
        int estimatedSeconds = (int) Math.round((totalDistance / 1.2) + (sortedItems.size() * 15.0));

        log.info("Optimized pick path for store {}: {} items, total distance {}m, est time {}s",
                storeId, sortedItems.size(), Math.round(totalDistance * 10.0) / 10.0, estimatedSeconds);

        return new OptimizedPathResponse(
                storeId,
                Math.round(totalDistance * 100.0) / 100.0,
                estimatedSeconds,
                pickSteps,
                new ArrayList<>(waypoints)
        );
    }
}
