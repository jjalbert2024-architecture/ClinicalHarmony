package com.clinicalharmony.fhircompliance.gold;

import com.clinicalharmony.fhircompliance.repository.DataLineageRecord;
import com.clinicalharmony.fhircompliance.repository.DataLineageRepository;
import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRecord;
import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRepository;
import com.clinicalharmony.fhircompliance.repository.MasterPatientRepository;
import com.clinicalharmony.fhircompliance.repository.silver.SilverPatientRow;
import com.clinicalharmony.fhircompliance.repository.silver.SilverTable;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one component that writes the Gold layer: gold.master_patients (+ patient_source_xref),
 * gold.fhir_resources and gold.data_lineage. Every Gold write path — Silver → FHIR builds (Slice
 * 1), Phase 5's caller-supplied /api/fhir-compliance/generate, and validation of rows seeded
 * straight into Gold (migration 19) — goes through here, so every Gold row gets a lineage row
 * and the same master-patient linking, whichever path produced it.
 *
 * <p><b>Master patients before Phase 6.</b> patient-index-service doesn't exist yet, so each
 * silver.patients row gets its own PROVISIONAL master (1:1, linked via patient_source_xref,
 * {@code match_confidence_score} NULL = "single-source, unmatched"). Phase 6 later matches/merges
 * those; until then the master is a stable Gold-side identity for the patient, and every Gold
 * resource referencing that patient is linked to it.
 *
 * <p><b>Lineage.</b> One gold.data_lineage row per (Gold row, step), upserted. Its
 * {@code transformation_details.steps} is the full chain — BRONZE → SILVER → GOLD where the Silver
 * row links to Bronze, SILVER → GOLD where it doesn't, SEED/API → GOLD for rows with no
 * Silver source — each {@code {stage, timestamp, transformer, input_hash, ref}}. GoldWriter
 * appends the GOLD step itself (it alone knows the Gold row id), with the stored JSON's hash as
 * {@code output_hash}.
 *
 * <p>Methods join the caller's transaction (callers run one per patient), so a patient's master,
 * resources and lineage commit or roll back together.
 */
@Component
public class GoldWriter {

    private static final Logger log = LoggerFactory.getLogger(GoldWriter.class);
    static final String PROVISIONAL_MASTER_TRANSFORMER =
            "fhir-compliance-service GoldWriter: provisional 1:1 master (pre-PMI; Phase 6 patient-index-service matches/merges)";

    private final GoldFhirResourceRepository fhirResources;
    private final MasterPatientRepository masterPatients;
    private final DataLineageRepository lineage;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public GoldWriter(GoldFhirResourceRepository fhirResources, MasterPatientRepository masterPatients,
                      DataLineageRepository lineage, ObjectMapper objectMapper) {
        this(fhirResources, masterPatients, lineage, objectMapper, Clock.systemUTC());
    }

    GoldWriter(GoldFhirResourceRepository fhirResources, MasterPatientRepository masterPatients,
               DataLineageRepository lineage, ObjectMapper objectMapper, Clock clock) {
        this.fhirResources = fhirResources;
        this.masterPatients = masterPatients;
        this.lineage = lineage;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Returns the master for this Silver patient, creating a PROVISIONAL 1:1 master (+ xref +
     * lineage) the first time. On later builds the existing master is reused and, while still
     * provisional, its demographics follow the Silver row.
     *
     * @param upstreamSteps the Silver patient's BRONZE/SILVER lineage steps
     */
    @Transactional
    public long ensureProvisionalMaster(SilverPatientRow patient, List<LineageStep> upstreamSteps) {
        masterPatients.lockSilverPatient(patient.id());
        long masterId = masterPatients.findMasterIdForSilverPatient(patient.id())
                .map(existing -> {
                    masterPatients.refreshProvisionalDemographics(existing, patient);
                    return existing;
                })
                .orElseGet(() -> {
                    long created = masterPatients.insertProvisional(patient);
                    masterPatients.insertSourceLink(created, patient.id());
                    log.info("Created PROVISIONAL gold.master_patients id={} for silver.patients id={}", created, patient.id());
                    return created;
                });

        String targetRef = "gold.master_patients/" + masterId;
        List<LineageStep> steps = withGoldStep(upstreamSteps, new LineageStep(LineageStep.GOLD, now(),
                PROVISIONAL_MASTER_TRANSFORMER, Hashes.sha256Hex(patient.toString()), null, targetRef));
        lineage.upsert(new DataLineageRecord("silver", SilverTable.PATIENTS.tableName(), patient.id(),
                "gold", "master_patients", masterId, LineageSource.PMI_PROVISIONAL,
                details(targetRef, steps)));
        return masterId;
    }

    /** Upserts one FHIR resource into gold.fhir_resources and its lineage row. Returns the Gold row id. */
    @Transactional
    public long writeResource(GoldResourceWrite write) {
        long goldId = fhirResources.upsert(new GoldFhirResourceRecord(write.resourceType(), write.fhirId(),
                write.resourceJson(), write.usCoreCompliant(), write.complianceNotes(), write.masterPatientId()));

        String targetRef = "gold.fhir_resources/" + goldId;
        List<LineageStep> steps = withGoldStep(write.upstreamSteps(), new LineageStep(LineageStep.GOLD, now(),
                write.goldTransformer(), write.goldInputHash(), Hashes.sha256Hex(write.resourceJson()), targetRef));
        LineageSource source = write.source();
        lineage.upsert(new DataLineageRecord(source.schema(), source.table(), source.id(),
                "gold", "fhir_resources", goldId, source.transformationStep(),
                details(write.resourceType() + "/" + write.fhirId(), steps)));
        log.debug("Gold write {}/{} → gold.fhir_resources id={} (compliant={}, master={})",
                write.resourceType(), write.fhirId(), goldId, write.usCoreCompliant(), write.masterPatientId());
        return goldId;
    }

    /**
     * Links every Gold resource referencing {@code Patient/<patientFhirId>} to the master —
     * including ones written before the master existed (the CMS-0057 seed's Coverage, Device,
     * MeasureReport). Returns the number of rows newly linked.
     */
    @Transactional
    public int linkPatientReferencesToMaster(String patientFhirId, long masterPatientId) {
        int linked = fhirResources.linkPatientReferencesToMaster(patientFhirId, masterPatientId);
        if (linked > 0) {
            log.info("Linked {} Gold resource(s) referencing Patient/{} to master {}", linked, patientFhirId, masterPatientId);
        }
        return linked;
    }

    private static List<LineageStep> withGoldStep(List<LineageStep> upstream, LineageStep gold) {
        List<LineageStep> steps = new ArrayList<>(upstream == null ? List.of() : upstream);
        steps.add(gold);
        return steps;
    }

    private String details(String target, List<LineageStep> steps) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("target", target);
        details.put("steps", steps);
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize lineage for " + target, e);
        }
    }

    private String now() {
        return Instant.now(clock).toString();
    }
}
