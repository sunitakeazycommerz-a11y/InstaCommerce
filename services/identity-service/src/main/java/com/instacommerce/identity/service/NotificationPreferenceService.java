package com.instacommerce.identity.service;

import com.instacommerce.identity.domain.model.NotificationPreference;
import com.instacommerce.identity.dto.request.NotificationPreferenceRequest;
import com.instacommerce.identity.dto.response.NotificationPreferenceResponse;
import com.instacommerce.identity.repository.NotificationPreferenceRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class NotificationPreferenceService {
    private final NotificationPreferenceRepository notificationPreferenceRepository;

    public NotificationPreferenceService(NotificationPreferenceRepository notificationPreferenceRepository) {
        this.notificationPreferenceRepository = notificationPreferenceRepository;
    }

    public NotificationPreferenceResponse getPreferences(UUID userId) {
        NotificationPreference preference = notificationPreferenceRepository.findById(userId)
            .orElseGet(() -> defaultPreference(userId));
        return toResponse(preference);
    }

    public NotificationPreferenceResponse updatePreferences(UUID userId, NotificationPreferenceRequest request) {
        NotificationPreference preference = notificationPreferenceRepository.findById(userId)
            .orElseGet(() -> newPreference(userId));
        preference.setEmailOptOut(request.emailOptOut());
        preference.setSmsOptOut(request.smsOptOut());
        preference.setPushOptOut(request.pushOptOut());
        preference.setMarketingOptOut(request.marketingOptOut());
        NotificationPreference saved = notificationPreferenceRepository.save(preference);
        return toResponse(saved);
    }

    private NotificationPreference defaultPreference(UUID userId) {
        NotificationPreference preference = newPreference(userId);
        preference.setEmailOptOut(false);
        preference.setSmsOptOut(false);
        preference.setPushOptOut(false);
        preference.setMarketingOptOut(false);
        return preference;
    }

    private NotificationPreference newPreference(UUID userId) {
        NotificationPreference preference = new NotificationPreference();
        preference.setUserId(userId);
        return preference;
    }

    private NotificationPreferenceResponse toResponse(NotificationPreference preference) {
        return new NotificationPreferenceResponse(
            preference.getUserId(),
            preference.isEmailOptOut(),
            preference.isSmsOptOut(),
            preference.isPushOptOut(),
            preference.isMarketingOptOut()
        );
    }
}
