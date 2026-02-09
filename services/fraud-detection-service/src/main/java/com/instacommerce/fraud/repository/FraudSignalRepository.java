package com.instacommerce.fraud.repository;

import com.instacommerce.fraud.domain.model.FraudSignal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudSignalRepository extends JpaRepository<FraudSignal, UUID> {
}
