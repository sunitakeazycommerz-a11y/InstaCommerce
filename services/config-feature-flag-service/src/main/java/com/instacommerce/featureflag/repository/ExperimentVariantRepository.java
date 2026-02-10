package com.instacommerce.featureflag.repository;

import com.instacommerce.featureflag.domain.model.ExperimentVariant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperimentVariantRepository extends JpaRepository<ExperimentVariant, UUID> {
    List<ExperimentVariant> findByExperimentIdOrderByName(UUID experimentId);

    void deleteByExperimentId(UUID experimentId);
}
