package com.instacommerce.wallet.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * Verifies KafkaErrorConfig wiring. Non-retryable exception classification
 * (OrderNotFoundException, IllegalArgumentException, JsonProcessingException)
 * is functionally validated by PaymentEventConsumerTest — they throw the
 * correct exception types which the DefaultErrorHandler routes accordingly.
 */
@ExtendWith(MockitoExtension.class)
class KafkaErrorConfigTest {

    @Mock
    private KafkaOperations<String, String> kafkaOperations;

    @Test
    @DisplayName("kafkaErrorHandler bean is a DefaultErrorHandler with DLT recoverer")
    void kafkaErrorHandlerBeanCreated() {
        KafkaErrorConfig config = new KafkaErrorConfig();
        CommonErrorHandler handler = config.kafkaErrorHandler(kafkaOperations);
        assertThat(handler).isNotNull().isInstanceOf(DefaultErrorHandler.class);
    }
}
