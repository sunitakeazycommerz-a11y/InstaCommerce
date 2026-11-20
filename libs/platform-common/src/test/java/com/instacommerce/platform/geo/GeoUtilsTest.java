package com.instacommerce.platform.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GeoUtils tests")
class GeoUtilsTest {

    @Test
    void returnsZeroForIdenticalCoordinates() {
        assertThat(GeoUtils.haversineKm(12.9716, 77.5946, 12.9716, 77.5946)).isEqualTo(0.0);
    }

    @Test
    void computesExpectedHaversineDistanceBetweenKnownPoints() {
        double bengaluruToKoramangala = GeoUtils.haversineKm(12.9716, 77.5946, 12.9279, 77.6271);
        assertThat(bengaluruToKoramangala).isCloseTo(6.0, within(0.5));

        double antipodal = GeoUtils.haversineKm(0.0, 0.0, 0.0, 180.0);
        assertThat(antipodal).isCloseTo(Math.PI * GeoUtils.EARTH_RADIUS_KM, within(0.01));
    }

    @Test
    void validatesCoordinateBoundsAndFiniteness() {
        assertThat(GeoUtils.isValidCoordinate(90.0, -180.0)).isTrue();
        assertThat(GeoUtils.isValidCoordinate(-90.0, 180.0)).isTrue();
        assertThat(GeoUtils.isValidCoordinate(90.1, 0.0)).isFalse();
        assertThat(GeoUtils.isValidCoordinate(0.0, -180.1)).isFalse();
        assertThat(GeoUtils.isValidCoordinate(Double.NaN, 77.5)).isFalse();
        assertThat(GeoUtils.isValidCoordinate(12.9, Double.POSITIVE_INFINITY)).isFalse();

        assertThatThrownBy(() -> GeoUtils.haversineKm(91.0, 77.0, 12.0, 77.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("origin latitude");
        assertThatThrownBy(() -> GeoUtils.haversineKm(12.0, 77.0, 12.0, 181.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destination longitude");
        assertThatThrownBy(() -> GeoUtils.haversineKm(Double.NaN, 77.0, 12.0, 77.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
