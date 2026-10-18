package com.instacommerce.routing.repository;

import com.instacommerce.routing.domain.model.WeatherCondition;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WeatherConditionRepository extends JpaRepository<WeatherCondition, UUID> {
    Optional<WeatherCondition> findByStoreId(UUID storeId);
}
