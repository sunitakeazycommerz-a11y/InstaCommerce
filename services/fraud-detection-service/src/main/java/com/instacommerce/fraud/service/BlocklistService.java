package com.instacommerce.fraud.service;

import com.instacommerce.fraud.domain.model.BlockedEntity;
import com.instacommerce.fraud.exception.BlockedEntityNotFoundException;
import com.instacommerce.fraud.repository.BlockedEntityRepository;
import java.time.Instant;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BlocklistService {

    private final BlockedEntityRepository blockedEntityRepository;

    public BlocklistService(BlockedEntityRepository blockedEntityRepository) {
        this.blockedEntityRepository = blockedEntityRepository;
    }

    @Cacheable(value = "blocklist", key = "#entityType + ':' + #entityValue")
    @Transactional(readOnly = true)
    public boolean isBlocked(String entityType, String entityValue) {
        if (entityType == null || entityType.isBlank() || entityValue == null || entityValue.isBlank()) {
            return false;
        }
        return blockedEntityRepository.findActiveBlockByEntityTypeAndValue(entityType, entityValue)
                .map(blocked -> {
                    if (blocked.getExpiresAt() != null && !blocked.getExpiresAt().isAfter(Instant.now())) {
                        return false;
                    }
                    return blocked.isActive();
                })
                .orElse(false);
    }

    @CacheEvict(value = "blocklist", key = "#entityType + ':' + #entityValue")
    @Transactional
    public BlockedEntity block(String entityType, String entityValue, String reason,
                               Instant expiresAt, String blockedBy) {
        // Deactivate existing blocks first and flush before inserting the new active row
        blockedEntityRepository.findActiveBlockByEntityTypeAndValue(entityType, entityValue)
                .ifPresent(existing -> {
                    existing.setActive(false);
                    blockedEntityRepository.saveAndFlush(existing);
                });

        BlockedEntity entity = new BlockedEntity();
        entity.setEntityType(entityType);
        entity.setEntityValue(entityValue);
        entity.setReason(reason);
        entity.setBlockedBy(blockedBy);
        entity.setBlockedAt(Instant.now());
        entity.setExpiresAt(expiresAt);
        entity.setActive(true);
        return blockedEntityRepository.save(entity);
    }

    @CacheEvict(value = "blocklist", key = "#entityType + ':' + #entityValue")
    @Transactional
    public void unblock(String entityType, String entityValue) {
        BlockedEntity entity = blockedEntityRepository
                .findActiveBlockByEntityTypeAndValue(entityType, entityValue)
                .orElseThrow(() -> new BlockedEntityNotFoundException(entityType, entityValue));
        entity.setActive(false);
        blockedEntityRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public Page<BlockedEntity> listActive(Pageable pageable) {
        return blockedEntityRepository.findByActiveTrue(pageable);
    }
}
