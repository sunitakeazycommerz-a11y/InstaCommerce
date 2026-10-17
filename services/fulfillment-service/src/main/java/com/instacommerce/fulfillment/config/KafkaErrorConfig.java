package com.instacommerce.fulfillment.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Global Kafka consumer error handler for fulfillment-service.
 *
 * <p>Requirements:
 * <ul>
 *   <li>Transient errors: retry up to 3 times with 1-second backoff.</li>
 *   <li>Exhausted retries: send to dead-letter topic {@code {topic}.DLT}.</li>
 *   <li>Permanent errors (bad JSON, invalid argument, null pointer): fail-fast to DLT without retry.</li>
 * </ul>
 *
 * @see <a href="../../../../../../docs/adr/003-kafka-dlt-naming.md">ADR-003</a>
 */
@Configuration
public class KafkaErrorConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorConfig.class);

    @Bean
    public CommonErrorHandler kafkaErrorHandler(ObjectProvider<KafkaOperations<String, String>> kafkaOperationsProvider) {
        return kafkaErrorHandler(kafkaOperationsProvider.getIfAvailable());
    }

    public CommonErrorHandler kafkaErrorHandler(KafkaOperations<String, String> kafkaOperations) {
        if (kafkaOperations != null) {
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

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(new FixedBackOff(1000L, 3));
        errorHandler.addNotRetryableExceptions(
            JsonProcessingException.class,
            IllegalArgumentException.class,
            NullPointerException.class
        );
        errorHandler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        return errorHandler;
    }
}
