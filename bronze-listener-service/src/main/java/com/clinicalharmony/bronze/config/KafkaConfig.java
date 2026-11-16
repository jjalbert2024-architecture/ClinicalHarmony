package com.clinicalharmony.bronze.config;

import com.clinicalharmony.bronze.listener.InvalidEventException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;

import java.nio.charset.StandardCharsets;

/**
 * Topics this service owns, and what happens when a record can't be processed.
 *
 * <ul>
 *   <li><b>Bad data</b> (an HL7 message that won't parse) is NOT an error here: it's stored as
 *       QUARANTINED and the offset moves on.</li>
 *   <li><b>Technical failure</b> (database down, …): {@code bronze.retry.max-retries} retries with
 *       exponential backoff, then the record goes to {@value Topics#HL7V2_RAW_DLT}, same partition.</li>
 *   <li><b>A broken event</b> (not JSON, no rawPayload): retrying can't fix it, so straight to the DLT.</li>
 * </ul>
 * Boot's auto-configured listener container factory picks the {@link DefaultErrorHandler} bean up.
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    NewTopic hl7v2RawDltTopic(BronzeProperties props) {
        return TopicBuilder.name(Topics.HL7V2_RAW_DLT).partitions(props.partitions()).replicas(1).build();
    }

    @Bean
    NewTopic bronzeIngestedTopic(BronzeProperties props) {
        return TopicBuilder.name(Topics.BRONZE_INGESTED).partitions(props.partitions()).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<?, ?> template, BronzeProperties props, MeterRegistry meters) {
        DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + ".dlt", record.partition()));
        ConsumerRecordRecoverer loggingRecoverer = (record, ex) -> {
            Throwable cause = rootCause(ex);
            log.atError().addKeyValue("event", "bronze.deadlettered")
                    .addKeyValue("correlationId", header(record, "correlation-id"))
                    .addKeyValue("causationId", header(record, "event-id"))
                    .addKeyValue("topic", record.topic())
                    .addKeyValue("partition", record.partition())
                    .addKeyValue("offset", record.offset())
                    .addKeyValue("dltTopic", record.topic() + ".dlt")
                    .addKeyValue("reason", cause.getClass().getSimpleName() + ": " + cause.getMessage())
                    .log("Record sent to dead-letter topic");
            meters.counter("clinicalharmony.bronze.messages", "outcome", "deadlettered").increment();
            dlt.accept(record, ex);
        };

        ExponentialBackOff backOff = new ExponentialBackOff(props.retry().initialInterval().toMillis(), props.retry().multiplier());
        backOff.setMaxAttempts(props.retry().maxRetries());
        DefaultErrorHandler handler = new DefaultErrorHandler(loggingRecoverer, backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class, DeserializationException.class);
        handler.setRetryListeners((record, ex, attempt) -> log.atWarn().addKeyValue("event", "bronze.delivery_failed")
                .addKeyValue("correlationId", header(record, "correlation-id"))
                .addKeyValue("topic", record.topic())
                .addKeyValue("partition", record.partition())
                .addKeyValue("offset", record.offset())
                .addKeyValue("attempt", attempt)
                .addKeyValue("reason", rootCause(ex).getMessage())
                .log("Failed to store record (retried with backoff unless non-retryable, then dead-lettered)"));
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
