package com.instacommerce.fraud.repository;

import com.instacommerce.fraud.domain.model.AllowlistedEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AllowlistRepository extends JpaRepository<AllowlistedEntity, UUID> {

    @Query("SELECT a FROM AllowlistedEntity a WHERE a.entityType = :entityType "
        + "AND a.entityValue = :entityValue AND a.active = true")
    Optional<AllowlistedEntity> findActiveAllowlistEntry(
        @Param("entityType") String entityType,
        @Param("entityValue") String entityValue);

    List<AllowlistedEntity> findByActiveTrue();
}
