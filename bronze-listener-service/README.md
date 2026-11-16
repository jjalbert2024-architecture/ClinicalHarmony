# bronze-listener-service

**Phase 9a — Bronze over Kafka.** Spring Boot 3.5 + Spring for Apache Kafka 3.3 + HAPI HL7 v2 2.3,
port **8086** (health / metrics only — its input is Kafka, not HTTP).

```
data-generator ──MLLP──▶ ingestion-service ──▶ clinicalharmony.bronze.hl7v2.raw (3 partitions)
                         (ACK AA after acks=all)          │  consumer group bronze-writer
                                                          ▼
                                     bronze-listener-service
                                       ├─ parses?  → bronze.raw_messages PENDING + outbox row   (one transaction)
                                       ├─ no       → bronze.raw_messages QUARANTINED + parse error
                                       ├─ seen     → skipped (bronze.duplicate)
                                       └─ DB down  → 3 retries (1 s, 2 s, 4 s) → clinicalharmony.bronze.hl7v2.raw.dlt
                                                          │
                                     outbox relay (500 ms) ▼
                                     clinicalharmony.bronze.ingested  → 9b silver transformer
```

## What it guarantees

| | How |
|---|---|
| **Every message kept** | Bad HL7 is a business outcome, not an error: stored `QUARANTINED` with the HAPI parse error (or "MSH-9 / MSH-10 is empty"), offset moves on. |
| **No duplicates** | Migration 23's partial unique index `(source_system, payload_checksum) WHERE intake_channel = 'MLLP_KAFKA'`; `INSERT … ON CONFLICT DO NOTHING`. A redelivered record or a re-sent message is logged `bronze.duplicate` and gets no second event. |
| **Offsets after commit** | `ack-mode: record`: the offset is committed only after the record's DB transaction commits. A crash in between → redelivery → duplicate skipped. |
| **Event never lost / never for a rolled-back row** | The `bronze.ingested` event is a row in `bronze.outbox_events`, written in the same transaction as the Bronze row. `OutboxRelay` sends unpublished rows in id order (`FOR UPDATE SKIP LOCKED`), stamps `published_at` only after Kafka confirms (acks=all), and stops the batch at the first failure so later events for a key can't overtake it. At-least-once: consumers must be idempotent. |
| **Order per patient** | Partition key from ingestion-service: `source system | PID-3` (fallback `source | msh10:<MSH-10>`, then `sha256:<checksum>`); the outbox event reuses it. 3 partitions, `concurrency: 3`. |
| **Technical failures** | `DefaultErrorHandler`: 3 retries with exponential backoff, then `<topic>.dlt` on the same partition with Spring Kafka's `kafka_dlt-exception-*` headers. A broken event (not JSON, no `rawPayload`, checksum ≠ payload) is non-retryable: straight to the DLT. |

## Events

**In** — `clinicalharmony.bronze.hl7v2.raw` (produced by ingestion-service), value:

```json
{"sourceSystem":"CITY_HOSPITAL","payloadChecksum":"<sha-256>","receivedAt":"2026-11-14T07:54:59Z",
 "hl7MessageType":"ADT^A01","controlId":"S42-00001","patientId":"CH123456","rawPayload":"MSH|^~\\&|…"}
```

**Out** — `clinicalharmony.bronze.ingested` (PENDING rows only), value:

```json
{"rawMessageId":303,"messageType":"HL7V2","hl7MessageType":"ADT^A01","sourceSystem":"CITY_HOSPITAL",
 "payloadChecksum":"<sha-256>","controlId":"S42-00001","receivedAt":"2026-11-14T07:54:59Z"}
```

Headers on both: `event-id`, `event-type` (`hl7v2.raw` / `bronze.ingested`), `schema-version` (1),
`produced-at`, `producer`, `correlation-id` (set once at MLLP intake, `mllp-<12 hex>`), and on the
output `causation-id` = the input record's `event-id`.

Each Bronze row also stores `intake_channel = MLLP_KAFKA`, `correlation_id`, `kafka_topic`,
`kafka_partition`, `kafka_offset`; `received_at` is the MLLP receipt time.

## Logs and metrics

One JSON object per line (Boot's Logstash layout), with `service`, `correlationId`, `causationId`
(MDC) and per-event key/values: `event` (`bronze.stored`, `bronze.quarantined`, `bronze.duplicate`,
`bronze.delivery_failed`, `bronze.deadlettered`, `bronze.published`, `bronze.outbox.publish_failed`),
`rawMessageId`, `topic`, `partition`, `offset`, `durationMs`. Trace one message across services:

```bash
docker logs clinicalharmony-ingestion-service 2>&1 | grep mllp-3fe4b6f3bff9
docker logs clinicalharmony-bronze-listener-service 2>&1 | grep mllp-3fe4b6f3bff9
```

Micrometer (`GET /actuator/metrics/<name>`): `clinicalharmony.bronze.messages` (tag `outcome` =
stored / quarantined / duplicate / deadlettered), `clinicalharmony.bronze.outbox.relayed`,
`clinicalharmony.bronze.outbox.backlog`. Consumer lag: Kafbat UI (`localhost:8090` → Consumers →
`bronze-writer`) or

```bash
docker exec clinicalharmony-kafka kafka-consumer-groups --bootstrap-server localhost:29092 --describe --group bronze-writer
```

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | `local` = `localhost:5432` + Kafka `localhost:19092` (for `mvn spring-boot:run`); `docker` = env vars below |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `postgres` / `5432` / `clinicalharmony` ×3 | docker profile |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:29092` | docker profile |

Tunables in `application.yml` under `bronze.*`: `partitions`, `retry.{max-retries, initial-interval, multiplier}`,
`outbox.{poll-interval, batch-size, send-timeout}`.

## Tests

```bash
mvn test   # 17 tests; the integration class needs Docker (skipped without it)
```

- `Hl7InspectorTest`, `Hl7RawEventTest`, `OutboxRelayTest` — plain JUnit.
- `BronzeListenerIntegrationTest` — `@SpringBootTest` with Testcontainers Postgres (initialized from
  `docs/db-init`, incl. migration 23) and `ConfluentKafkaContainer` (`confluentinc/cp-kafka:7.6.1`, KRaft —
  the compose image): PENDING + announced with lineage headers, QUARANTINED + not announced, redelivery
  skipped, listener stopped mid-run then resumed (20/20, no duplicates), broken event and checksum
  mismatch straight to the DLT, a persistent DB error retried then dead-lettered.

End to end: `scripts/smoke-test.sh` section 18, and the Postman walkthrough in
[`postman/phase9a-bronze-over-kafka.postman_collection.json`](postman/phase9a-bronze-over-kafka.postman_collection.json).
