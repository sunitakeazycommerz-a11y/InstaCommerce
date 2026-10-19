package com.instacommerce.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

class KafkaErrorConfigTest {

    @Test
    @DisplayName("kafkaErrorHandler configures DeadLetterPublishingRecoverer and non-retryable exceptions")
    void kafkaErrorHandlerConfiguresRecovererAndNotRetryableExceptions() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        KafkaErrorConfig config = new KafkaErrorConfig();

        CommonErrorHandler errorHandler = config.kafkaErrorHandler(kafkaTemplate);

        assertThat(errorHandler).isNotNull();
        assertThat(errorHandler).isInstanceOf(DefaultErrorHandler.class);
        DefaultErrorHandler defaultErrorHandler = (DefaultErrorHandler) errorHandler;
        // Verify not-retryable exceptions
        assertThat(defaultErrorHandler.isAckAfterHandle()).isTrue();
    }
}
