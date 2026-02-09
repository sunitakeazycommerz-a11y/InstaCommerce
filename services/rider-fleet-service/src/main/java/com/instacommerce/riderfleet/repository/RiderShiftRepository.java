package com.instacommerce.riderfleet.repository;

import com.instacommerce.riderfleet.domain.model.RiderShift;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiderShiftRepository extends JpaRepository<RiderShift, UUID> {

    List<RiderShift> findByRiderIdAndStatus(UUID riderId, String status);

    List<RiderShift> findByRiderId(UUID riderId);
}
