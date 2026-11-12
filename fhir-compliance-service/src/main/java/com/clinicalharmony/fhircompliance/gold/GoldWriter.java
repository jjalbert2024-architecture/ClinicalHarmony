package com.clinicalharmony.fhircompliance.gold;

import com.clinicalharmony.fhircompliance.repository.DataLineageRecord;
import com.clinicalharmony.fhircompliance.repository.DataLineageRepository;
import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRecord;
import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRepository;
import com.clinicalharmony.fhircompliance.repository.MasterMergeRepository;
import com.clinicalharmony.fhircompliance.repository.MasterMergeRow;
import com.clinicalharmony.fhircompliance.repository.MasterPatientRepository;
import com.clinicalharmony.fhircompliance.repository.MasterPatientSnapshot;
import com.clinicalharmony.fhircompliance.repository.silver.SilverPatientRow;
import com.clinicalharmony.fhircompliance.repository.silver.SilverTable;
import com.clinicalharmony.fhircompliance.service.silver.FhirIds;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
 * <p><b>Master patients.</b> Each silver.patients row first gets its own PROVISIONAL master (1:1,
 * linked via patient_source_xref, {@code match_confidence_score} NULL = "single-source, unmatched"),
 * and every Gold resource referencing that patient is linked to it. patient-index-service (Phase 6)
 * decides which masters are the same person but never writes Gold itself: it calls
 * {@link #confirmMaster}, {@link #mergeMasters} and {@link #unmergeMasters} (over HTTP, via
 * {@code /api/fhir/gold/masters/**}), each one transaction that moves xref rows and the
 * {@code gold.fhir_resources.master_patient_id} links together and records the merge in
 * gold.master_patient_merges so it can be undone.
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
    static final String PMI_TRANSFORMER = "patient-index-service → fhir-compliance-service GoldWriter: ";
    static final String PROVISIONAL_MASTER_TRANSFORMER =
            "fhir-compliance-service GoldWriter: provisional 1:1 master (pre-PMI; Phase 6 patient-index-service matches/merges)";

    private final GoldFhirResourceRepository fhirResources;
    private final MasterPatientRepository masterPatients;
    private final MasterMergeRepository merges;
    private final DataLineageRepository lineage;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public GoldWriter(GoldFhirResourceRepository fhirResources, MasterPatientRepository masterPatients,
                      MasterMergeRepository merges, DataLineageRepository lineage, ObjectMapper objectMapper) {
        this(fhirResources, masterPatients, merges, lineage, objectMapper, Clock.systemUTC());
    }

    GoldWriter(GoldFhirResourceRepository fhirResources, MasterPatientRepository masterPatients,
               MasterMergeRepository merges, DataLineageRepository lineage, ObjectMapper objectMapper, Clock clock) {
        this.fhirResources = fhirResources;
        this.masterPatients = masterPatients;
        this.merges = merges;
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

    // ---------------------------------------------------------------------------------------------
    // Phase 6: decisions from patient-index-service
    // ---------------------------------------------------------------------------------------------

    /**
     * PROVISIONAL → MATCHED for a master the PMI found no duplicate for. Idempotent on a master
     * that is already MATCHED; refuses a MERGED one.
     */
    @Transactional
    public MasterPatientSnapshot confirmMaster(long masterPatientId, String decidedBy) {
        MasterPatientSnapshot master = masterPatients.lock(masterPatientId)
                .orElseThrow(() -> new GoldMasterNotFoundException("No gold.master_patients row with id " + masterPatientId));
        if (!master.active()) {
            throw new GoldMasterConflictException("Master " + masterPatientId + " is " + master.matchStatus()
                    + mergedIntoSuffix(masterPatientId) + " — confirm the surviving master instead");
        }
        if (!MasterPatientSnapshot.PROVISIONAL.equals(master.matchStatus())) {
            return master;
        }
        MasterPatientSnapshot confirmed = masterPatients.markMatched(masterPatientId, merges.minLiveScore(masterPatientId));
        String targetRef = "gold.master_patients/" + masterPatientId;
        lineage.upsert(new DataLineageRecord("gold", "master_patients", masterPatientId, "gold", "master_patients",
                masterPatientId, LineageSource.PMI_MATCH, details(targetRef, List.of(new LineageStep(LineageStep.GOLD,
                        now(), PMI_TRANSFORMER + "confirm (no duplicate found) — " + decidedBy, null, null, targetRef)))));
        log.info("Confirmed master {} as MATCHED (decided by {})", masterPatientId, decidedBy);
        return confirmed;
    }

    /**
     * Absorbs one master into another: the absorbed master's xref rows and every Gold resource
     * linked to it move to the survivor, the absorbed master becomes MERGED / inactive, and the
     * survivor becomes MATCHED with confidence = its weakest live merge. All or nothing.
     */
    @Transactional
    public MasterMergeResult mergeMasters(MasterMergeCommand command) {
        long survivorId = command.survivorMasterId();
        long absorbedId = command.absorbedMasterId();
        if (survivorId == absorbedId) {
            throw new GoldMasterConflictException("A master can't be merged into itself (id " + survivorId + ")");
        }
        // Lock in id order so two merges touching the same pair can't deadlock.
        MasterPatientSnapshot first = lockExisting(Math.min(survivorId, absorbedId));
        MasterPatientSnapshot second = lockExisting(Math.max(survivorId, absorbedId));
        for (MasterPatientSnapshot m : List.of(first, second)) {
            if (!m.active()) {
                throw new GoldMasterConflictException("Master " + m.id() + " is " + m.matchStatus()
                        + mergedIntoSuffix(m.id()) + "; only active masters can be merged");
            }
        }

        List<Long> movedSilver = masterPatients.moveSourceLinks(absorbedId, survivorId);
        List<Long> movedResources = fhirResources.repointMaster(absorbedId, survivorId);
        long mergeId = merges.insert(survivorId, absorbedId, movedSilver, movedResources,
                command.matchConfidenceScore(), command.matchMethod(), command.evidenceJson(), command.decidedBy());
        MasterPatientSnapshot absorbed = masterPatients.markMerged(absorbedId);
        MasterPatientSnapshot survivor = masterPatients.markMatched(survivorId, merges.minLiveScore(survivorId));

        String targetRef = "gold.master_patient_merges/" + mergeId;
        lineage.upsert(new DataLineageRecord("gold", "master_patients", absorbedId, "gold", "master_patient_merges",
                mergeId, LineageSource.PMI_MERGE, details(targetRef, List.of(new LineageStep(LineageStep.GOLD, now(),
                        PMI_TRANSFORMER + "merge master " + absorbedId + " into " + survivorId + " ("
                                + command.matchMethod() + scoreSuffix(command.matchConfidenceScore()) + ") — "
                                + command.decidedBy(),
                        command.evidenceJson() == null ? null : Hashes.sha256Hex(command.evidenceJson()),
                        null, targetRef)))));
        log.info("Merged master {} into {} (merge {}, {}{}): moved {} Silver link(s), {} Gold resource(s)",
                absorbedId, survivorId, mergeId, command.matchMethod(), scoreSuffix(command.matchConfidenceScore()),
                movedSilver.size(), movedResources.size());
        return new MasterMergeResult(mergeId, survivor, absorbed, movedSilver, movedResources);
    }

    /**
     * Undoes one merge: the Silver links and Gold resources it moved go back to the absorbed
     * master, which becomes active again (MATCHED — a human has now said it's a different person).
     * Refused if the survivor has since been merged into a third master (undo that merge first).
     */
    @Transactional
    public MasterUnmergeResult unmergeMasters(long mergeId, String decidedBy, String reason) {
        MasterMergeRow merge = merges.lockById(mergeId)
                .orElseThrow(() -> new GoldMasterNotFoundException("No gold.master_patient_merges row with id " + mergeId));
        if (merge.unmergedAt() != null) {
            throw new GoldMasterConflictException("Merge " + mergeId + " was already undone at " + merge.unmergedAt());
        }
        long survivorId = merge.survivorMasterId();
        long absorbedId = merge.absorbedMasterId();
        lockExisting(Math.min(survivorId, absorbedId));
        lockExisting(Math.max(survivorId, absorbedId));
        merges.findLiveMergeAbsorbing(survivorId).ifPresent(later -> {
            throw new GoldMasterConflictException("Master " + survivorId + " has since been merged into master "
                    + later.survivorMasterId() + " (merge " + later.id() + "); undo that merge first");
        });

        List<Long> silverIds = merge.movedSilverPatientIds();
        int movedBack = masterPatients.moveSourceLinks(silverIds, survivorId, absorbedId);
        if (movedBack != silverIds.size()) {
            throw new GoldMasterConflictException("Only " + movedBack + " of the " + silverIds.size()
                    + " Silver patient(s) merge " + mergeId + " moved are still on master " + survivorId);
        }
        int restoredResources = fhirResources.repointIds(merge.movedFhirResourceIds(), survivorId, absorbedId);
        // Resources built for these Silver patients after the merge were linked to the survivor; follow them back.
        for (long silverId : silverIds) {
            restoredResources += fhirResources.linkPatientReferencesToMaster(FhirIds.patient(silverId), absorbedId);
        }
        merges.markUnmerged(mergeId, decidedBy, reason);
        MasterPatientSnapshot restored = masterPatients.markMatched(absorbedId, merges.minLiveScore(absorbedId));
        MasterPatientSnapshot survivor = masterPatients.markMatched(survivorId, merges.minLiveScore(survivorId));

        String targetRef = "gold.master_patient_merges/" + mergeId;
        lineage.upsert(new DataLineageRecord("gold", "master_patients", survivorId, "gold", "master_patient_merges",
                mergeId, LineageSource.PMI_UNMERGE, details(targetRef, List.of(new LineageStep(LineageStep.GOLD, now(),
                        PMI_TRANSFORMER + "unmerge master " + absorbedId + " from " + survivorId + " — " + decidedBy
                                + (reason == null || reason.isBlank() ? "" : ": " + reason),
                        null, null, targetRef)))));
        log.info("Unmerged master {} from {} (merge {}): restored {} Silver link(s), {} Gold resource(s)",
                absorbedId, survivorId, mergeId, silverIds.size(), restoredResources);
        return new MasterUnmergeResult(mergeId, survivor, restored, silverIds, restoredResources);
    }

    private MasterPatientSnapshot lockExisting(long masterPatientId) {
        return masterPatients.lock(masterPatientId)
                .orElseThrow(() -> new GoldMasterNotFoundException("No gold.master_patients row with id " + masterPatientId));
    }

    private String mergedIntoSuffix(long masterPatientId) {
        return merges.findLiveMergeAbsorbing(masterPatientId)
                .map(m -> " (merged into master " + m.survivorMasterId() + " by merge " + m.id() + ")")
                .orElse("");
    }

    private static String scoreSuffix(BigDecimal score) {
        return score == null ? "" : ", score " + score.toPlainString();
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
