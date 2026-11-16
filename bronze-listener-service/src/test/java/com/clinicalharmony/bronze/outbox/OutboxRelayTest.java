package com.clinicalharmony.bronze.outbox;

import com.clinicalharmony.bronze.config.BronzeProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    private final OutboxRepository repo = mock(OutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final BronzeProperties props = new BronzeProperties(3, null,
            new BronzeProperties.Outbox(Duration.ofMillis(500), 100, Duration.ofSeconds(1)), null);
    private final OutboxRelay relay = new OutboxRelay(repo, kafka, mock(TransactionTemplate.class), props, new SimpleMeterRegistry());

    private static OutboxRepository.OutboxEvent event(long id) {
        return new OutboxRepository.OutboxEvent(id, UUID.randomUUID(), "bronze.ingested", "clinicalharmony.bronze.ingested",
                "K|1", "{\"rawMessageId\":" + id + "}", Map.of("correlation-id", "c" + id, "causation-id", "e" + id), id, 0);
    }

    @SuppressWarnings("unchecked")
    @Test
    void stopsAtFirstFailureSoLaterEventsCannotOvertakeIt() {
        when(repo.lockBatch(100)).thenReturn(List.of(event(1), event(2), event(3)));
        RecordMetadata meta = new RecordMetadata(new TopicPartition("clinicalharmony.bronze.ingested", 0), 5, 0, 0, 0, 0);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, meta)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        int sent = relay.relayBatch();

        assertThat(sent).isEqualTo(1);
        verify(repo).markPublished(1L);
        verify(repo).markFailed(org.mockito.ArgumentMatchers.eq(2L), anyString());
        verify(repo, never()).markPublished(3L);
        verify(repo, never()).markFailed(org.mockito.ArgumentMatchers.eq(3L), anyString());
    }

    @Test
    void emptyBatchSendsNothing() {
        when(repo.lockBatch(100)).thenReturn(List.of());
        assertThat(relay.relayBatch()).isZero();
        verify(repo, never()).markPublished(anyLong());
    }
}
