package com.instacommerce.fraud.repository;

import com.instacommerce.fraud.domain.model.FraudRule;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FraudRuleRepository extends JpaRepository<FraudRule, UUID> {

    @Query("SELECT r FROM FraudRule r WHERE r.active = true ORDER BY r.priority ASC")
    List<FraudRule> findActiveOrderByPriority();
}
