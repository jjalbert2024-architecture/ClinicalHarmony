package com.clinicalharmony.fhircompliance.goldbuilder;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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

import java.nio.charset.StandardCharsets;

/**
 * Phase 9c gold-builder wiring: the topics this service owns and what happens when a record can't be processed.
 *
 * <ul>
 *   <li><b>Bad data</b> (a Silver patient whose FHIR fails US Core) is NOT an error: it is written to Gold with
 *       its violations in compliance_notes, like every other build.</li>
 *   <li><b>Technical failure</b> (database down, …): {@code gold-builder.retry.max-retries} retries with
 *       exponential backoff, then {@value Topics#SILVER_PATIENT_CHANGED_DLT}, same partition.</li>
 *   <li><b>A broken event</b> (not JSON, no silverPatientId, unknown Silver patient): straight to the DLT.</li>
 * </ul>
 * Boot's auto-configured listener container factory picks the {@link DefaultErrorHandler} bean up.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(GoldBuilderProperties.class)
@ConditionalOnProperty(prefix = "gold-builder", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GoldBuilderKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(GoldBuilderKafkaConfig.class);

    @Bean
    NewTopic silverPatientChangedDltTopic(GoldBuilderProperties props) {
        return TopicBuilder.name(Topics.SILVER_PATIENT_CHANGED_DLT).partitions(props.partitions()).replicas(1).build();
    }

    @Bean
    NewTopic goldPatientChangedTopic(GoldBuilderProperties props) {
        return TopicBuilder.name(Topics.GOLD_PATIENT_CHANGED).partitions(props.partitions()).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<?, ?> template, GoldBuilderProperties props, MeterRegistry meters) {
        DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + ".dlt", record.partition()));
        ConsumerRecordRecoverer loggingRecoverer = (record, ex) -> {
            Throwable cause = rootCause(ex);
            log.atError().addKeyValue("event", "gold.deadlettered")
                    .addKeyValue("correlationId", header(record, "correlation-id"))
                    .addKeyValue("causationId", header(record, "event-id"))
                    .addKeyValue("topic", record.topic())
                    .addKeyValue("partition", record.partition())
                    .addKeyValue("offset", record.offset())
                    .addKeyValue("dltTopic", record.topic() + ".dlt")
                    .addKeyValue("reason", cause.getClass().getSimpleName() + ": " + cause.getMessage())
                    .log("Record sent to dead-letter topic");
            meters.counter("clinicalharmony.gold.builds", "outcome", "deadlettered").increment();
            dlt.accept(record, ex);
        };

        ExponentialBackOff backOff = new ExponentialBackOff(props.retry().initialInterval().toMillis(), props.retry().multiplier());
        backOff.setMaxAttempts(props.retry().maxRetries());
        DefaultErrorHandler handler = new DefaultErrorHandler(loggingRecoverer, backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class, DeserializationException.class);
        handler.setRetryListeners((record, ex, attempt) -> log.atWarn().addKeyValue("event", "gold.delivery_failed")
                .addKeyValue("correlationId", header(record, "correlation-id"))
                .addKeyValue("topic", record.topic())
                .addKeyValue("partition", record.partition())
                .addKeyValue("offset", record.offset())
                .addKeyValue("attempt", attempt)
                .addKeyValue("reason", rootCause(ex).getMessage())
                .log("Failed to build Gold for record (retried with backoff unless non-retryable, then dead-lettered)"));
        return handler;
    }

    static Throwable rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c;
    }

    static String header(ConsumerRecord<?, ?> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
