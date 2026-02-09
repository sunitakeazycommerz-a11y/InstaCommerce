package com.instacommerce.riderfleet.repository;

import com.instacommerce.riderfleet.domain.model.RiderRating;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiderRatingRepository extends JpaRepository<RiderRating, UUID> {

    List<RiderRating> findByRiderId(UUID riderId);
}
