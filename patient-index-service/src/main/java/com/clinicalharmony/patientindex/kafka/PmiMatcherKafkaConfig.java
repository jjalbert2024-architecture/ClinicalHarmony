package com.clinicalharmony.patientindex.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Phase 9c pmi-matcher wiring. The listener only records a signal in memory, so the only realistic failure is a
 * broken record (→ {@value Topics#GOLD_PATIENT_CHANGED_DLT} at once); anything else gets the same retry-then-DLT
 * handling as the other Phase 9 consumers. A failed matching run is not a record failure: the debouncer retries it.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(PmiMatcherProperties.class)
@ConditionalOnProperty(prefix = "pmi-matcher", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PmiMatcherKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(PmiMatcherKafkaConfig.class);

    @Bean
    NewTopic goldPatientChangedDltTopic(PmiMatcherProperties props) {
        return TopicBuilder.name(Topics.GOLD_PATIENT_CHANGED_DLT).partitions(props.partitions()).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<?, ?> template, PmiMatcherProperties props) {
        DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + ".dlt", record.partition()));
        ConsumerRecordRecoverer loggingRecoverer = (record, ex) -> {
            Throwable cause = ex;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            log.atError().addKeyValue("event", "pmi.deadlettered")
                    .addKeyValue("topic", record.topic())
                    .addKeyValue("partition", record.partition())
                    .addKeyValue("offset", record.offset())
                    .addKeyValue("dltTopic", record.topic() + ".dlt")
                    .addKeyValue("reason", cause.getClass().getSimpleName() + ": " + cause.getMessage())
                    .log("Record sent to dead-letter topic");
            dlt.accept(record, ex);
        };
        ExponentialBackOff backOff = new ExponentialBackOff(props.retry().initialInterval().toMillis(), props.retry().multiplier());
        backOff.setMaxAttempts(props.retry().maxRetries());
        DefaultErrorHandler handler = new DefaultErrorHandler(loggingRecoverer, backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class, DeserializationException.class);
        return handler;
    }
}
