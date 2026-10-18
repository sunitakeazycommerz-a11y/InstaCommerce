package com.instacommerce.fulfillment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.instacommerce.fulfillment.domain.model.PickItem;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AisleRoutingOptimizerTest {

    private AisleRoutingOptimizer optimizer;

    @BeforeEach
    void setUp() {
        optimizer = new AisleRoutingOptimizer();
    }

    @Test
    @DisplayName("Should sort items in S-curve serpentine order: even aisles shelf ASC, odd aisles shelf DESC")
    void shouldOptimizePickPathInSCurveSerpentineOrder() {
        List<PickItem> items = new ArrayList<>();

        // Aisle A01 (index 0, even) -> forward: shelf ASC (1 -> 3)
        items.add(createItem("A01", 3, "BIN-01", "Item A3"));
        items.add(createItem("A01", 1, "BIN-02", "Item A1"));

        // Aisle A02 (index 1, odd) -> backward: shelf DESC (4 -> 1)
        items.add(createItem("A02", 1, "BIN-01", "Item B1"));
        items.add(createItem("A02", 4, "BIN-02", "Item B4"));

        // Aisle A03 (index 2, even) -> forward: shelf ASC (2 -> 5)
        items.add(createItem("A03", 5, "BIN-01", "Item C5"));
        items.add(createItem("A03", 2, "BIN-02", "Item C2"));

        List<PickItem> optimized = optimizer.optimizePickPath(items);

        assertThat(optimized).hasSize(6);

        // Aisle A01 forward: shelf 1 then shelf 3
        assertThat(optimized.get(0).getProductName()).isEqualTo("Item A1");
        assertThat(optimized.get(0).getPickSequence()).isEqualTo(1);
        assertThat(optimized.get(1).getProductName()).isEqualTo("Item A3");
        assertThat(optimized.get(1).getPickSequence()).isEqualTo(2);

        // Aisle A02 backward: shelf 4 then shelf 1
        assertThat(optimized.get(2).getProductName()).isEqualTo("Item B4");
        assertThat(optimized.get(2).getPickSequence()).isEqualTo(3);
        assertThat(optimized.get(3).getProductName()).isEqualTo("Item B1");
        assertThat(optimized.get(3).getPickSequence()).isEqualTo(4);

        // Aisle A03 forward: shelf 2 then shelf 5
        assertThat(optimized.get(4).getProductName()).isEqualTo("Item C2");
        assertThat(optimized.get(4).getPickSequence()).isEqualTo(5);
        assertThat(optimized.get(5).getProductName()).isEqualTo("Item C5");
        assertThat(optimized.get(5).getPickSequence()).isEqualTo(6);
    }

    @Test
    @DisplayName("Should assign virtual aisle coordinates when items have no explicit location")
    void shouldHandleMissingAisleCoordinatesGracefully() {
        PickItem item1 = new PickItem();
        item1.setSku("MILK-101");
        item1.setProductName("Organic Milk 1L");

        PickItem item2 = new PickItem();
        item2.setSku("BREAD-202");
        item2.setProductName("Sourdough Bread");

        List<PickItem> optimized = optimizer.optimizePickPath(List.of(item1, item2));

        assertThat(optimized).hasSize(2);
        assertThat(optimized.get(0).getAisle()).isNotNull().startsWith("A");
        assertThat(optimized.get(0).getPickSequence()).isEqualTo(1);
        assertThat(optimized.get(1).getPickSequence()).isEqualTo(2);
    }

    private PickItem createItem(String aisle, int shelf, String bin, String name) {
        PickItem item = new PickItem();
        item.setAisle(aisle);
        item.setShelf(shelf);
        item.setBin(bin);
        item.setProductName(name);
        return item;
    }
}
