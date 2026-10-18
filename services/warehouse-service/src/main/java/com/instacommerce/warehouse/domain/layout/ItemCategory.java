package com.instacommerce.warehouse.domain.layout;

public enum ItemCategory {
    HEAVY(1, "Heavy Goods (Beverages, Rice, Flour, Oil)"),
    AMBIENT(2, "Ambient & Packaged Goods"),
    CHILLED(3, "Chilled & Dairy"),
    FROZEN(4, "Frozen Foods"),
    FRAGILE(5, "Fragile & Bakery (Bread, Eggs, Pastries, Chips)");

    private final int weightOrder;
    private final String description;

    ItemCategory(int weightOrder, String description) {
        this.weightOrder = weightOrder;
        this.description = description;
    }

    public int getWeightOrder() {
        return weightOrder;
    }

    public String getDescription() {
        return description;
    }

    public static ItemCategory fromString(String value) {
        if (value == null || value.isBlank()) {
            return AMBIENT;
        }
        String normalized = value.trim().toUpperCase();
        for (ItemCategory cat : values()) {
            if (cat.name().equals(normalized)) {
                return cat;
            }
        }
        // Match common keywords
        if (normalized.contains("HEAVY") || normalized.contains("BEVERAGE") || normalized.contains("FLOUR") || normalized.contains("RICE")) {
            return HEAVY;
        }
        if (normalized.contains("FRAGILE") || normalized.contains("BAKERY") || normalized.contains("BREAD") || normalized.contains("EGG")) {
            return FRAGILE;
        }
        if (normalized.contains("CHILLED") || normalized.contains("DAIRY") || normalized.contains("MILK")) {
            return CHILLED;
        }
        if (normalized.contains("FROZEN") || normalized.contains("ICE_CREAM")) {
            return FROZEN;
        }
        return AMBIENT;
    }
}
