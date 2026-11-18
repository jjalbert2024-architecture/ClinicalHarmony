package com.clinicalharmony.fhircompliance.gold;

import com.clinicalharmony.fhircompliance.repository.silver.SilverProvenance;
import com.clinicalharmony.fhircompliance.repository.silver.SilverProvenanceRepository;
import com.clinicalharmony.fhircompliance.repository.silver.SilverTable;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the pre-Gold lineage steps for a Silver row: BRONZE (only when the row links back to
 * bronze.raw_messages) then SILVER.
 *
 * <p>Rows written by the Kafka pipeline (Phase 9a/9b: MLLP → ingestion-service → Kafka →
 * bronze-listener-service → Bronze, then silver-transformer-service → Silver) link back to their raw
 * HL7 message, and the step transformers name that path. Seed rows (migrations 17, 21) have no
 * raw_message_id: their chain starts at SILVER with a null input_hash — the honest answer, rather
 * than guessing which Bronze message they "came from".
 */
@Component
public class SilverLineage {

    static final String INGESTION_TRANSFORMER = "ingestion-service";
    static final String KAFKA_INGESTION_TRANSFORMER = "ingestion-service MLLP → Kafka → bronze-listener-service";
    static final String KAFKA_SILVER_TRANSFORMER = "silver-transformer-service";

    private final SilverProvenanceRepository provenanceRepository;

    public SilverLineage(SilverProvenanceRepository provenanceRepository) {
        this.provenanceRepository = provenanceRepository;
    }

    public List<LineageStep> stepsFor(SilverTable table, long id) {
        return provenanceRepository.find(table, id)
                .map(p -> steps(table, id, p))
                .orElseGet(List::of);
    }

    static List<LineageStep> steps(SilverTable table, long id, SilverProvenance p) {
        List<LineageStep> steps = new ArrayList<>(2);
        if (p.hasBronzeLink()) {
            steps.add(new LineageStep(LineageStep.BRONZE, iso(p.bronzeReceivedAt()),
                    (p.viaKafka() ? KAFKA_INGESTION_TRANSFORMER : INGESTION_TRANSFORMER)
                            + " (" + p.bronzeMessageType() + ")",
                    p.bronzePayloadChecksum(), null, "bronze.raw_messages/" + p.rawMessageId()));
            steps.add(new LineageStep(LineageStep.SILVER, iso(p.silverCreatedAt()),
                    (p.viaKafka() ? KAFKA_SILVER_TRANSFORMER + ": " : "")
                            + "Bronze → Silver parse (source_system=" + p.sourceSystem() + ")",
                    p.bronzePayloadChecksum(), null, table.ref(id)));
        } else {
            steps.add(new LineageStep(LineageStep.SILVER, iso(p.silverCreatedAt()),
                    "Silver load with no Bronze link (source_system=" + p.sourceSystem() + ")",
                    null, null, table.ref(id)));
        }
        return steps;
    }

    private static String iso(OffsetDateTime t) {
        return t == null ? null : t.toInstant().toString();
    }
}
