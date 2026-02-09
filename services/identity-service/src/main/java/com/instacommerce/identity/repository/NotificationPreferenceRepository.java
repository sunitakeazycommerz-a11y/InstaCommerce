package com.instacommerce.identity.repository;

import com.instacommerce.identity.domain.model.NotificationPreference;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {
}
