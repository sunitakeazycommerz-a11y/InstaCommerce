package com.instacommerce.fraud.repository;

import com.instacommerce.fraud.domain.model.BlockedEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlockedEntityRepository extends JpaRepository<BlockedEntity, UUID> {

    @Query("SELECT b FROM BlockedEntity b WHERE b.entityType = :entityType " +
           "AND b.entityValue = :entityValue AND b.active = true")
    Optional<BlockedEntity> findActiveBlockByEntityTypeAndValue(
            @Param("entityType") String entityType,
            @Param("entityValue") String entityValue);

    Page<BlockedEntity> findByActiveTrue(Pageable pageable);
}
