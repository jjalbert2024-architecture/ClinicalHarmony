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
 * <p>No service parses Bronze into Silver yet, so in practice the Silver rows today (migration 17's
 * seed) have no raw_message_id and their chain starts at SILVER, with a null input_hash — the
 * honest answer, rather than guessing which Bronze message they "came from". Once a Bronze → Silver
 * parser sets raw_message_id, the BRONZE step appears with no change here.
 */
@Component
public class SilverLineage {

    static final String INGESTION_TRANSFORMER = "ingestion-service";

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
                    INGESTION_TRANSFORMER + " (" + p.bronzeMessageType() + ")",
                    p.bronzePayloadChecksum(), null, "bronze.raw_messages/" + p.rawMessageId()));
            steps.add(new LineageStep(LineageStep.SILVER, iso(p.silverCreatedAt()),
                    "Bronze → Silver parse (source_system=" + p.sourceSystem() + ")",
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
