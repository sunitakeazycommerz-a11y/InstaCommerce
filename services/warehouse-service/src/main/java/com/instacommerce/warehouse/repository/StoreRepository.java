package com.instacommerce.warehouse.repository;

import com.instacommerce.warehouse.domain.model.Store;
import com.instacommerce.warehouse.domain.model.StoreStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreRepository extends JpaRepository<Store, UUID> {

    List<Store> findByStatus(StoreStatus status);

    List<Store> findByCity(String city);

    @Query(value = """
            SELECT * FROM (
                SELECT s.*, (
                    6371 * acos(
                        LEAST(1.0, cos(radians(:lat)) * cos(radians(s.latitude))
                        * cos(radians(s.longitude) - radians(:lng))
                        + sin(radians(:lat)) * sin(radians(s.latitude)))
                    )
                ) AS distance_km
                FROM stores s
                WHERE s.status = 'ACTIVE'
                  AND s.latitude BETWEEN :lat - (:radiusKm / 111.0) AND :lat + (:radiusKm / 111.0)
                  AND s.longitude BETWEEN :lng - (:radiusKm / (111.0 * cos(radians(:lat))))
                                       AND :lng + (:radiusKm / (111.0 * cos(radians(:lat))))
            ) sub
            WHERE sub.distance_km <= :radiusKm
            ORDER BY sub.distance_km
            LIMIT :maxResults
            """, nativeQuery = true)
    List<Store> findNearestStores(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusKm") double radiusKm,
            @Param("maxResults") int maxResults);
}
