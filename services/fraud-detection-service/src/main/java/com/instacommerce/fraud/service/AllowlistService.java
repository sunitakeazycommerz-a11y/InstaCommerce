package com.instacommerce.fraud.service;

import com.instacommerce.fraud.domain.model.AllowlistedEntity;
import com.instacommerce.fraud.dto.request.BlockRequest;
import com.instacommerce.fraud.exception.ApiException;
import com.instacommerce.fraud.repository.AllowlistRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AllowlistService {

    private static final Logger log = LoggerFactory.getLogger(AllowlistService.class);

    private final AllowlistRepository allowlistRepository;
    private final Clock clock;

    @Autowired
    public AllowlistService(AllowlistRepository allowlistRepository) {
        this(allowlistRepository, Clock.systemUTC());
    }

    public AllowlistService(AllowlistRepository allowlistRepository, Clock clock) {
        this.allowlistRepository = allowlistRepository;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    public boolean isAllowlisted(String entityType, String entityValue) {
        if (entityType == null || entityType.isBlank() || entityValue == null || entityValue.isBlank()) {
            return false;
        }
        String normalizedType = entityType.trim().toUpperCase(Locale.ROOT);
        String normalizedValue = entityValue.trim();
        Instant now = Instant.now(clock);
        return allowlistRepository.findActiveAllowlistEntry(normalizedType, normalizedValue)
            .filter(entry -> entry.getExpiresAt() == null || entry.getExpiresAt().isAfter(now))
            .isPresent();
    }

    @Transactional
    public AllowlistedEntity allowEntity(BlockRequest request, String allowedBy) {
        String normalizedType = request.entityType().trim().toUpperCase(Locale.ROOT);
        String normalizedValue = request.entityValue().trim();
        Instant now = Instant.now(clock);
        if (request.expiresAt() != null && !request.expiresAt().isAfter(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EXPIRY",
                "Allowlist expiry must be in the future");
        }
        allowlistRepository.findActiveAllowlistEntry(normalizedType, normalizedValue)
            .ifPresent(existing -> {
                existing.setActive(false);
                allowlistRepository.saveAndFlush(existing);
            });
        AllowlistedEntity entity = new AllowlistedEntity();
        entity.setEntityType(normalizedType);
        entity.setEntityValue(normalizedValue);
        entity.setReason(request.reason());
        entity.setAllowedBy(allowedBy);
        entity.setAllowedAt(now);
        entity.setExpiresAt(request.expiresAt());
        entity.setActive(true);
        AllowlistedEntity saved = allowlistRepository.save(entity);
        log.info("Allowlisted entity type={} value={} by={}", normalizedType, normalizedValue, allowedBy);
        return saved;
    }

    @Transactional
    public void revokeAllowlist(UUID id) {
        AllowlistedEntity entity = allowlistRepository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ALLOWLIST_NOT_FOUND",
                "Allowlisted entity not found: " + id));
        entity.setActive(false);
        allowlistRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<AllowlistedEntity> listActive() {
        Instant now = Instant.now(clock);
        return allowlistRepository.findByActiveTrue().stream()
            .filter(entry -> entry.getExpiresAt() == null || entry.getExpiresAt().isAfter(now))
            .toList();
    }
}
