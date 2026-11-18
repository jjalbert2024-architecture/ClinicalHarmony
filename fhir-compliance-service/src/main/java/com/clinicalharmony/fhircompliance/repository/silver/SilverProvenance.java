package com.clinicalharmony.fhircompliance.repository.silver;

import java.time.OffsetDateTime;

/**
 * Where a Silver row came from: when it was written, its patient's source system, and — when it
 * links back to Bronze via raw_message_id — that Bronze row's checksum, arrival time and type.
 * {@code bronzeIntakeChannel} is REST or MLLP_KAFKA (migration 23). The bronze* fields are all null when there's no Bronze link (e.g. migration 17's seed rows).
 */
public record SilverProvenance(
        OffsetDateTime silverCreatedAt,
        String sourceSystem,
        Long rawMessageId,
        String bronzePayloadChecksum,
        OffsetDateTime bronzeReceivedAt,
        String bronzeMessageType,
        String bronzeIntakeChannel
) {
    /** Bronze row written by the Phase 9a Kafka path (MLLP → Kafka → bronze-listener-service). */
    public static final String MLLP_KAFKA = "MLLP_KAFKA";

    public boolean viaKafka() {
        return MLLP_KAFKA.equals(bronzeIntakeChannel);
    }

    public boolean hasBronzeLink() {
        return rawMessageId != null;
    }
}
