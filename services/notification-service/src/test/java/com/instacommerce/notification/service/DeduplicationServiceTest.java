package com.instacommerce.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.notification.domain.model.NotificationChannel;
import com.instacommerce.notification.domain.model.NotificationLog;
import com.instacommerce.notification.domain.model.NotificationStatus;
import com.instacommerce.notification.dto.NotificationRequest;
import com.instacommerce.notification.repository.NotificationLogRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class DeduplicationServiceTest {

    @Mock
    private NotificationLogRepository logRepository;

    @InjectMocks
    private DeduplicationService deduplicationService;

    @Test
    @DisplayName("createLog persists maskedRecipient instead of raw plaintext recipient")
    void createLogPersistsMaskedRecipient() {
        NotificationRequest request = new NotificationRequest(
            "evt-123",
            "OrderPlaced",
            UUID.randomUUID(),
            NotificationChannel.EMAIL,
            "order_confirmed",
            "alice@example.com",
            Map.of()
        );
        when(logRepository.save(any(NotificationLog.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationLog saved = deduplicationService.createLog(request, "a***@example.com");

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepository).save(captor.capture());
        assertThat(saved).isNotNull();
        assertThat(captor.getValue().getRecipient()).isEqualTo("a***@example.com");
        assertThat(captor.getValue().getRecipient()).doesNotContain("alice@example.com");
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    @DisplayName("createLog returns null on duplicate event and channel")
    void createLogReturnsNullOnDuplicate() {
        NotificationRequest request = new NotificationRequest(
            "evt-dup",
            "OrderPlaced",
            UUID.randomUUID(),
            NotificationChannel.SMS,
            "order_confirmed",
            "+919876543210",
            Map.of()
        );
        when(logRepository.save(any(NotificationLog.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate"));

        NotificationLog result = deduplicationService.createLog(request, "+91****3210");

        assertThat(result).isNull();
    }
}
