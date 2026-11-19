package com.instacommerce.riderfleet.service;

import com.instacommerce.riderfleet.domain.model.RiderShift;
import com.instacommerce.riderfleet.repository.RiderShiftRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderShiftService {

    private final RiderShiftRepository shiftRepository;

    public RiderShiftService(RiderShiftRepository shiftRepository) {
        this.shiftRepository = shiftRepository;
    }

    @Transactional(readOnly = true)
    public boolean hasActiveShift(UUID riderId, Instant at) {
        List<RiderShift> activeShifts = shiftRepository.findByRiderIdAndStatus(riderId, "ACTIVE");
        for (RiderShift shift : activeShifts) {
            if (!at.isBefore(shift.getShiftStart()) && at.isBefore(shift.getShiftEnd())) {
                return true;
            }
        }
        return false;
    }
}
