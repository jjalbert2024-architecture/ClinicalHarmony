package com.clinicalharmony.fhircompliance.service.silver;

import ca.uhn.fhir.context.FhirContext;
import com.clinicalharmony.fhircompliance.gold.ComplianceNotes;
import com.clinicalharmony.fhircompliance.gold.GoldResourceWrite;
import com.clinicalharmony.fhircompliance.gold.GoldWriter;
import com.clinicalharmony.fhircompliance.gold.Hashes;
import com.clinicalharmony.fhircompliance.gold.LineageSource;
import com.clinicalharmony.fhircompliance.gold.SilverLineage;
import com.clinicalharmony.fhircompliance.repository.silver.SilverClinicalRepository;
import com.clinicalharmony.fhircompliance.repository.silver.SilverPatientRow;
import com.clinicalharmony.fhircompliance.repository.silver.SilverTable;
import com.clinicalharmony.fhircompliance.validation.FhirValidationOutcome;
import com.clinicalharmony.fhircompliance.validation.FhirViolation;
import com.clinicalharmony.fhircompliance.validation.UsCoreFhirValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Silver → Gold FHIR materialization: reads one silver.patients row plus its conditions,
 * observations and medications, builds US Core resources, validates each with the HL7 validator,
 * and hands them to {@link GoldWriter} — which links them to the patient's (provisional) master,
 * upserts them into gold.fhir_resources with the findings in {@code compliance_notes}, and writes
 * each one's gold.data_lineage chain back through Silver (and Bronze, where linked).
 *
 * <p>Validation records, it doesn't gate: a non-compliant resource still lands in Gold, flagged
 * {@code is_us_core_compliant = false} with the reasons attached — dropping it would hide the
 * data-quality problem from the dashboard instead of surfacing it.
 *
 * <p>Each patient's master, resources and lineage are written in one transaction (all or nothing
 * per patient); a build-all run uses a transaction per patient so one bad patient can't block the rest.
 */
@Service
public class SilverFhirBuildService {

    private static final Logger log = LoggerFactory.getLogger(SilverFhirBuildService.class);

    private final SilverClinicalRepository silverRepository;
    private final PatientResourceBuilder patientBuilder;
    private final ConditionResourceBuilder conditionBuilder;
    private final ObservationResourceBuilder observationBuilder;
    private final MedicationRequestResourceBuilder medicationBuilder;
    private final UsCoreFhirValidator validator;
    private final GoldWriter goldWriter;
    private final SilverLineage silverLineage;
    private final FhirContext fhirContext;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public SilverFhirBuildService(SilverClinicalRepository silverRepository,
                                  PatientResourceBuilder patientBuilder,
                                  ConditionResourceBuilder conditionBuilder,
                                  ObservationResourceBuilder observationBuilder,
                                  MedicationRequestResourceBuilder medicationBuilder,
                                  UsCoreFhirValidator validator,
                                  GoldWriter goldWriter,
                                  SilverLineage silverLineage,
                                  FhirContext fhirContext,
                                  ObjectMapper objectMapper,
                                  TransactionTemplate transactionTemplate) {
        this(silverRepository, patientBuilder, conditionBuilder, observationBuilder, medicationBuilder, validator,
                goldWriter, silverLineage, fhirContext, objectMapper, transactionTemplate, Clock.systemUTC());
    }

    SilverFhirBuildService(SilverClinicalRepository silverRepository,
                           PatientResourceBuilder patientBuilder,
                           ConditionResourceBuilder conditionBuilder,
                           ObservationResourceBuilder observationBuilder,
                           MedicationRequestResourceBuilder medicationBuilder,
                           UsCoreFhirValidator validator,
                           GoldWriter goldWriter,
                           SilverLineage silverLineage,
                           FhirContext fhirContext,
                           ObjectMapper objectMapper,
                           TransactionTemplate transactionTemplate,
                           Clock clock) {
        this.silverRepository = silverRepository;
        this.patientBuilder = patientBuilder;
        this.conditionBuilder = conditionBuilder;
        this.observationBuilder = observationBuilder;
        this.medicationBuilder = medicationBuilder;
        this.validator = validator;
        this.goldWriter = goldWriter;
        this.silverLineage = silverLineage;
        this.fhirContext = fhirContext;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** Builds, validates and persists every resource for one Silver patient. */
    public SilverPatientBuildResult buildPatient(long silverPatientId) {
        SilverPatientRow patientRow = silverRepository.findPatient(silverPatientId)
                .orElseThrow(() -> new SilverPatientNotFoundException(silverPatientId));
        return transactionTemplate.execute(status -> buildAndPersist(patientRow));
    }

    /** Builds every Silver patient, each in its own transaction. */
    public List<SilverPatientBuildResult> buildAllPatients() {
        List<Long> ids = silverRepository.findAllPatientIds();
        log.info("Building FHIR resources for {} Silver patient(s)", ids.size());
        List<SilverPatientBuildResult> results = new ArrayList<>(ids.size());
        for (long id : ids) {
            results.add(buildPatient(id));
        }
        return results;
    }

    /** A built resource plus the Silver row it came from (for lineage). */
    private record Built(Resource resource, SilverTable table, long silverId, String silverRowHash, String builder) {
    }

    private SilverPatientBuildResult buildAndPersist(SilverPatientRow patientRow) {
        long patientId = patientRow.id();
        String patientFhirId = FhirIds.patient(patientId);
        long masterId = goldWriter.ensureProvisionalMaster(patientRow,
                silverLineage.stepsFor(SilverTable.PATIENTS, patientId));

        List<Built> built = new ArrayList<>();
        built.add(new Built(patientBuilder.build(patientRow), SilverTable.PATIENTS, patientId,
                Hashes.sha256Hex(patientRow.toString()), "PatientResourceBuilder"));
        silverRepository.findConditions(patientId).forEach(c -> built.add(new Built(
                conditionBuilder.build(c, patientFhirId), SilverTable.CONDITIONS, c.id(),
                Hashes.sha256Hex(c.toString()), "ConditionResourceBuilder")));
        silverRepository.findObservations(patientId).forEach(o -> built.add(new Built(
                observationBuilder.build(o, patientFhirId), SilverTable.OBSERVATIONS, o.id(),
                Hashes.sha256Hex(o.toString()), "ObservationResourceBuilder")));
        silverRepository.findMedications(patientId).forEach(m -> built.add(new Built(
                medicationBuilder.build(m, patientFhirId), SilverTable.MEDICATIONS, m.id(),
                Hashes.sha256Hex(m.toString()), "MedicationRequestResourceBuilder")));

        List<BuiltResourceSummary> summaries = built.stream().map(b -> validateAndPersist(b, masterId)).toList();
        // Resources written before this patient had a master (e.g. the CMS-0057 seed's Coverage) get linked now.
        goldWriter.linkPatientReferencesToMaster(patientFhirId, masterId);

        int compliant = (int) summaries.stream().filter(BuiltResourceSummary::usCoreCompliant).count();
        log.info("Silver patient {} → master {}, {} FHIR resource(s), {} US Core compliant",
                patientId, masterId, summaries.size(), compliant);
        return new SilverPatientBuildResult(patientId, patientFhirId, masterId, summaries.size(), compliant, summaries);
    }

    private BuiltResourceSummary validateAndPersist(Built built, long masterId) {
        Resource resource = built.resource();
        String json = fhirContext.newJsonParser().encodeResourceToString(resource);
        FhirValidationOutcome outcome = validator.validateJson(json);
        String resourceType = resource.fhirType();
        String fhirId = resource.getIdElement().getIdPart();

        long goldId = goldWriter.writeResource(new GoldResourceWrite(
                resourceType, fhirId, json, outcome.compliant(),
                ComplianceNotes.toJson(outcome, Instant.now(clock), objectMapper),
                masterId,
                LineageSource.silver(built.table().tableName(), built.silverId(), LineageSource.FHIR_MAP),
                silverLineage.stepsFor(built.table(), built.silverId()),
                "fhir-compliance-service " + built.builder() + " + HL7 validator (" + outcome.profile() + ")",
                built.silverRowHash()));

        List<FhirViolation> errors = outcome.violations().stream()
                .filter(v -> "ERROR".equals(v.severity()))
                .toList();
        if (!outcome.compliant()) {
            log.warn("{}/{} is NOT US Core compliant ({} error(s)); stored in Gold with compliance notes",
                    resourceType, fhirId, outcome.errorCount());
        }
        return new BuiltResourceSummary(resourceType, fhirId, goldId, outcome.compliant(),
                outcome.errorCount(), outcome.warningCount(), errors);
    }
}
