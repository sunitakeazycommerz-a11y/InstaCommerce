package com.instacommerce.fraud.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.fraud.domain.model.BlockedEntity;
import com.instacommerce.fraud.exception.BlockedEntityNotFoundException;
import com.instacommerce.fraud.repository.BlockedEntityRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlocklistServiceTest {

    @Mock
    private BlockedEntityRepository repository;

    private BlocklistService blocklistService;

    @BeforeEach
    void setUp() {
        blocklistService = new BlocklistService(repository);
    }

    @Test
    @DisplayName("isBlocked returns true when active non-expired block exists")
    void isBlocked_activeNonExpired_returnsTrue() {
        BlockedEntity entity = new BlockedEntity();
        entity.setEntityType("USER");
        entity.setEntityValue("user-123");
        entity.setActive(true);
        entity.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));

        when(repository.findActiveBlockByEntityTypeAndValue("USER", "user-123")).thenReturn(Optional.of(entity));

        boolean blocked = blocklistService.isBlocked("USER", "user-123");
        assertThat(blocked).isTrue();
    }

    @Test
    @DisplayName("isBlocked returns false when active block has expired")
    void isBlocked_expired_returnsFalse() {
        BlockedEntity entity = new BlockedEntity();
        entity.setEntityType("IP");
        entity.setEntityValue("1.2.3.4");
        entity.setActive(true);
        entity.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));

        when(repository.findActiveBlockByEntityTypeAndValue("IP", "1.2.3.4")).thenReturn(Optional.of(entity));

        boolean blocked = blocklistService.isBlocked("IP", "1.2.3.4");
        assertThat(blocked).isFalse();
    }

    @Test
    @DisplayName("isBlocked returns false when no block record found")
    void isBlocked_notFound_returnsFalse() {
        when(repository.findActiveBlockByEntityTypeAndValue("DEVICE", "dev-999")).thenReturn(Optional.empty());

        boolean blocked = blocklistService.isBlocked("DEVICE", "dev-999");
        assertThat(blocked).isFalse();
    }

    @Test
    @DisplayName("block deactivates existing block and saves new active block")
    void block_deactivatesExistingAndSavesNew() {
        BlockedEntity existing = new BlockedEntity();
        existing.setEntityType("USER");
        existing.setEntityValue("user-abc");
        existing.setActive(true);

        when(repository.findActiveBlockByEntityTypeAndValue("USER", "user-abc")).thenReturn(Optional.of(existing));
        when(repository.save(any(BlockedEntity.class))).thenAnswer(i -> i.getArgument(0));

        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        BlockedEntity saved = blocklistService.block("USER", "user-abc", "Suspicious chargeback", expiresAt, "admin-1");

        assertThat(existing.isActive()).isFalse();
        assertThat(saved.getEntityType()).isEqualTo("USER");
        assertThat(saved.getEntityValue()).isEqualTo("user-abc");
        assertThat(saved.getReason()).isEqualTo("Suspicious chargeback");
        assertThat(saved.getBlockedBy()).isEqualTo("admin-1");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("unblock deactivates active block successfully")
    void unblock_deactivatesBlock() {
        BlockedEntity active = new BlockedEntity();
        active.setEntityType("USER");
        active.setEntityValue("user-1");
        active.setActive(true);

        when(repository.findActiveBlockByEntityTypeAndValue("USER", "user-1")).thenReturn(Optional.of(active));

        blocklistService.unblock("USER", "user-1");

        assertThat(active.isActive()).isFalse();
        verify(repository).save(active);
    }

    @Test
    @DisplayName("unblock throws BlockedEntityNotFoundException when no active block found")
    void unblock_notFound_throwsException() {
        when(repository.findActiveBlockByEntityTypeAndValue("USER", "user-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> blocklistService.unblock("USER", "user-missing"))
            .isInstanceOf(BlockedEntityNotFoundException.class);
    }
}
