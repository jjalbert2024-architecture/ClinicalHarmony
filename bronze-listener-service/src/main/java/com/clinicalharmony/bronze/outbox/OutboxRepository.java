package com.clinicalharmony.bronze.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code bronze.outbox_events} (migration 23). Every method joins the caller's transaction. */
@Repository
public class OutboxRepository {

    public record OutboxEvent(long id, UUID eventId, String eventType, String topic, String key, String payload,
                              Map<String, String> headers, Long aggregateId, int attempts) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OutboxRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public UUID insert(String topic, String eventType, String key, Map<String, Object> payload,
                       Map<String, String> headers, long aggregateId) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bronze.outbox_events (event_id, event_type, topic, message_key, payload, headers, aggregate_id)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """, eventId, eventType, topic, key, json(payload), json(headers), aggregateId);
        return eventId;
    }

    /**
     * Oldest unpublished events, row-locked; SKIP LOCKED lets a second relay instance take the next
     * rows instead of blocking — and never sends the same row twice concurrently.
     */
    public List<OutboxEvent> lockBatch(int limit) {
        return jdbc.query("""
                SELECT id, event_id, event_type, topic, message_key, payload::text AS payload, headers::text AS headers,
                       aggregate_id, attempts
                FROM bronze.outbox_events
                WHERE published_at IS NULL
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (rs, i) -> new OutboxEvent(rs.getLong("id"), rs.getObject("event_id", UUID.class),
                rs.getString("event_type"), rs.getString("topic"), rs.getString("message_key"), rs.getString("payload"),
                headers(rs.getString("headers")), (Long) rs.getObject("aggregate_id"), rs.getInt("attempts")), limit);
    }

    public void markPublished(long id) {
        jdbc.update("UPDATE bronze.outbox_events SET published_at = now(), attempts = attempts + 1, last_error = NULL WHERE id = ?", id);
    }

    public void markFailed(long id, String error) {
        jdbc.update("UPDATE bronze.outbox_events SET attempts = attempts + 1, last_error = left(?, 1000) WHERE id = ?", error, id);
    }

    public long backlog() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM bronze.outbox_events WHERE published_at IS NULL", Long.class);
        return n == null ? 0 : n;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> headers(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
