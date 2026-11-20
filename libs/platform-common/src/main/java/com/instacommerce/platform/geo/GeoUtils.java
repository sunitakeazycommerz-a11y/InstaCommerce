package com.instacommerce.platform.geo;

/**
 * Geographic coordinate validation and spherical haversine distance utilities.
 */
public final class GeoUtils {
    public static final double EARTH_RADIUS_KM = 6371.0;

    private GeoUtils() {
    }

    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        validateCoordinate(lat1, lng1, "origin");
        validateCoordinate(lat2, lng2, "destination");
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double sinHalfLat = Math.sin(dLat / 2.0);
        double sinHalfLng = Math.sin(dLng / 2.0);
        double a = sinHalfLat * sinHalfLat
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sinHalfLng * sinHalfLng;
        double clampedA = Math.min(1.0, Math.max(0.0, a));
        double c = 2.0 * Math.atan2(Math.sqrt(clampedA), Math.sqrt(1.0 - clampedA));
        return EARTH_RADIUS_KM * c;
    }

    public static boolean isValidCoordinate(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= -90.0 && lat <= 90.0
                && lng >= -180.0 && lng <= 180.0;
    }

    private static void validateCoordinate(double lat, double lng, String label) {
        if (!Double.isFinite(lat) || lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException(label + " latitude must be finite and in [-90, 90]: " + lat);
        }
        if (!Double.isFinite(lng) || lng < -180.0 || lng > 180.0) {
            throw new IllegalArgumentException(label + " longitude must be finite and in [-180, 180]: " + lng);
        }
    }
}
