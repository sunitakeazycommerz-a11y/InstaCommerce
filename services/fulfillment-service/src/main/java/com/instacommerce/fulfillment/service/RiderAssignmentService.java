package com.instacommerce.fulfillment.service;

import com.instacommerce.fulfillment.domain.model.Rider;
import com.instacommerce.fulfillment.exception.NoAvailableRiderException;
import com.instacommerce.fulfillment.repository.RiderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderAssignmentService {
    private final RiderRepository riderRepository;

    public RiderAssignmentService(RiderRepository riderRepository) {
        this.riderRepository = riderRepository;
    }

    @Transactional
    public Rider assignRider(String storeId) {
        return riderRepository.findNextAvailableForStore(storeId)
            .orElseThrow(() -> new NoAvailableRiderException(storeId));
    }
}
