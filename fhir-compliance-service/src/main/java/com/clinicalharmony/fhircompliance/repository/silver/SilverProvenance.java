package com.clinicalharmony.fhircompliance.repository.silver;

import java.time.OffsetDateTime;

/**
 * Where a Silver row came from: when it was written, its patient's source system, and — when it
 * links back to Bronze via raw_message_id — that Bronze row's checksum, arrival time and type.
 * The bronze* fields are all null when there's no Bronze link (e.g. migration 17's seed rows).
 */
public record SilverProvenance(
        OffsetDateTime silverCreatedAt,
        String sourceSystem,
        Long rawMessageId,
        String bronzePayloadChecksum,
        OffsetDateTime bronzeReceivedAt,
        String bronzeMessageType
) {
    public boolean hasBronzeLink() {
        return rawMessageId != null;
    }
}
