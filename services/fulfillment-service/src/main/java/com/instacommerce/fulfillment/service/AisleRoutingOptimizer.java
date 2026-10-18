package com.instacommerce.fulfillment.service;

import com.instacommerce.fulfillment.domain.model.PickItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Optimizes warehouse pick paths using the standard Traveling Salesperson (TSP)
 * S-curve (serpentine) heuristic for physical dark store layouts.
 *
 * Traversal Strategy:
 * 1. Items are clustered by physical aisle (sorted in natural ascending order: A01 -> A02 -> A03...).
 * 2. On even-indexed aisles (0th, 2nd, 4th...), the picker walks forward: shelf ASC (1 -> N), bin ASC.
 * 3. On odd-indexed aisles (1st, 3rd, 5th...), the picker walks backward: shelf DESC (N -> 1), bin DESC.
 * 4. Assigns sequential pick sequence indices (1-based) to minimize overall picker walking distance.
 */
@Component
public class AisleRoutingOptimizer {

    public List<PickItem> optimizePickPath(List<PickItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        // Group by aisle with natural ordering (e.g., A01, A02, B01)
        Map<String, List<PickItem>> itemsByAisle = items.stream()
            .collect(Collectors.groupingBy(
                item -> resolveAisle(item),
                TreeMap::new,
                Collectors.toList()
            ));

        List<PickItem> sortedResult = new ArrayList<>(items.size());
        int aisleIndex = 0;

        for (Map.Entry<String, List<PickItem>> entry : itemsByAisle.entrySet()) {
            List<PickItem> aisleItems = entry.getValue();
            boolean forward = (aisleIndex % 2 == 0);

            aisleItems.sort((a, b) -> {
                int shelfA = a.getShelf() != null ? a.getShelf() : 1;
                int shelfB = b.getShelf() != null ? b.getShelf() : 1;
                int shelfCmp = forward ? Integer.compare(shelfA, shelfB) : Integer.compare(shelfB, shelfA);
                if (shelfCmp != 0) {
                    return shelfCmp;
                }
                String binA = a.getBin() != null ? a.getBin() : "";
                String binB = b.getBin() != null ? b.getBin() : "";
                return forward ? binA.compareTo(binB) : binB.compareTo(binA);
            });

            sortedResult.addAll(aisleItems);
            aisleIndex++;
        }

        // Assign 1-based sequential pick order
        for (int i = 0; i < sortedResult.size(); i++) {
            sortedResult.get(i).setPickSequence(i + 1);
        }

        return sortedResult;
    }

    public static String resolveAisle(PickItem item) {
        if (item.getAisle() != null && !item.getAisle().isBlank()) {
            return item.getAisle().trim();
        }
        // Deterministic fallback based on SKU if not explicitly assigned
        if (item.getSku() != null && !item.getSku().isBlank()) {
            int hash = Math.abs(item.getSku().hashCode()) % 10 + 1;
            String computedAisle = String.format("A%02d", hash);
            item.setAisle(computedAisle);
            if (item.getShelf() == null) {
                item.setShelf(Math.abs(item.getSku().hashCode()) % 5 + 1);
            }
            if (item.getBin() == null) {
                item.setBin(String.format("BIN-%02d", Math.abs(item.getSku().hashCode()) % 20 + 1));
            }
            return computedAisle;
        }
        return "A01";
    }
}
