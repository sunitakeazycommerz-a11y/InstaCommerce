package com.instacommerce.wallet.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.instacommerce.wallet.exception.OrderNotFoundException;
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
 * <p>Follows the standard repo pattern (see order-service, fulfillment-service).
 *
 * <ul>
 *   <li>Retryable (up to 3 retries, 1 s apart): HTTP 5xx from order-service,
 *       network timeouts, transient DB errors — any exception not listed below.</li>
 *   <li>Non-retryable (sent to DLT immediately): malformed JSON, missing/invalid
 *       payload fields, order confirmed not-found (404).</li>
 * </ul>
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

        // Non-retryable: data quality / permanent failures — skip retry, go straight to DLT
        errorHandler.addNotRetryableExceptions(
            JsonProcessingException.class,     // malformed event JSON
            IllegalArgumentException.class,    // missing required fields, invalid values
            OrderNotFoundException.class       // order-service 404: order genuinely absent
        );

        errorHandler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        return errorHandler;
    }
}
