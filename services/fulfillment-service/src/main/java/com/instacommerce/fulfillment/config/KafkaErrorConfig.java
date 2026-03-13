package com.instacommerce.fulfillment.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka consumer error handling: retries transient failures with backoff,
 * routes exhausted and non-retryable records to a dead-letter topic (*.DLT).
 *
 * <p>Non-retryable exceptions (sent to DLT immediately): malformed JSON,
 * invalid arguments, null pointers from missing required fields.
 *
 * <p>Retryable (up to 3 retries, 1s apart): all other exceptions
 * (transient DB, network, downstream service errors).
 *
 * @see <a href="../../../../../../docs/adr/003-kafka-dlt-naming.md">ADR-003</a>
 */
@Configuration
public class KafkaErrorConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorConfig.class);

    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaOperations<String, String> kafkaOperations) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations,
            (record, ex) -> {
                log.error("Sending record to DLT: topic={}, key={}, cause={}",
                    record.topic(), record.key(),
                    ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                return new TopicPartition(record.topic() + ".DLT", record.partition());
            });

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));

        errorHandler.addNotRetryableExceptions(
            JsonProcessingException.class,
            IllegalArgumentException.class,
            NullPointerException.class
        );

        errorHandler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        return errorHandler;
    }
}
