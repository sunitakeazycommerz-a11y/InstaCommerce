package com.instacommerce.featureflag.repository;

import com.instacommerce.featureflag.domain.model.ExperimentExposure;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperimentExposureRepository extends JpaRepository<ExperimentExposure, UUID> {
}
