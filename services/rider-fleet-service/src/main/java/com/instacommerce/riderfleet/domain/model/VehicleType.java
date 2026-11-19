package com.instacommerce.riderfleet.domain.model;

public enum VehicleType {
    BICYCLE(8_000),
    MOTORCYCLE(25_000),
    CAR(100_000);

    private final int defaultCapacityGrams;

    VehicleType(int defaultCapacityGrams) {
        this.defaultCapacityGrams = defaultCapacityGrams;
    }

    public int getDefaultCapacityGrams() {
        return defaultCapacityGrams;
    }

    public boolean canCarry(int orderWeightGrams) {
        return orderWeightGrams <= defaultCapacityGrams;
    }
}
