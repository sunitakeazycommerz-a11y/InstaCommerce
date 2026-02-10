package com.instacommerce.featureflag.repository;

import com.instacommerce.featureflag.domain.model.Experiment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperimentRepository extends JpaRepository<Experiment, UUID> {
    Optional<Experiment> findByKey(String key);
}
