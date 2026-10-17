package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.domain.CompensationDlq;
import com.instacommerce.checkout.repository.CompensationDlqRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CompensationDlqActivityImplTest {

    @Test
    @DisplayName("recordFailedCompensation logs error and saves CompensationDlq entity")
    void shouldSaveFailedCompensationToDlq() {
        CompensationDlqRepository repository = mock(CompensationDlqRepository.class);
        CompensationDlqActivityImpl activity = new CompensationDlqActivityImpl(repository);

        activity.recordFailedCompensation(
            "workflow-123",
            "releaseStock",
            "res-456",
            "Inventory service deadlock",
            "{\"items\":[{\"id\":\"prod-1\"}]}"
        );

        ArgumentCaptor<CompensationDlq> captor = ArgumentCaptor.forClass(CompensationDlq.class);
        verify(repository).save(captor.capture());

        CompensationDlq saved = captor.getValue();
        assertThat(saved.getWorkflowId()).isEqualTo("workflow-123");
        assertThat(saved.getActivityName()).isEqualTo("releaseStock");
        assertThat(saved.getTargetId()).isEqualTo("res-456");
        assertThat(saved.getReason()).isEqualTo("Inventory service deadlock");
        assertThat(saved.getPayload()).contains("prod-1");
        assertThat(saved.getStatus()).isEqualTo("FAILED");
        assertThat(saved.getRetryCount()).isEqualTo(0);
        assertThat(saved.getCreatedAt()).isNotNull();
    }
}
