package com.instacommerce.routing.repository;

import com.instacommerce.routing.domain.model.TrafficCongestionFeed;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TrafficCongestionFeedRepository extends JpaRepository<TrafficCongestionFeed, UUID> {
    Optional<TrafficCongestionFeed> findByZoneId(String zoneId);
}
