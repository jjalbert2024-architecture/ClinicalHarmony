package com.clinicalharmony.fhircompliance.service.silver;

import ca.uhn.fhir.context.FhirContext;
import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRecord;
import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRepository;
import com.clinicalharmony.fhircompliance.repository.silver.SilverClinicalRepository;
import com.clinicalharmony.fhircompliance.repository.silver.SilverPatientRow;
import com.clinicalharmony.fhircompliance.validation.FhirValidationOutcome;
import com.clinicalharmony.fhircompliance.validation.FhirViolation;
import com.clinicalharmony.fhircompliance.validation.UsCoreFhirValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Silver → Gold FHIR materialization: reads one silver.patients row plus its conditions,
 * observations and medications, builds US Core resources, validates each with the HL7 validator,
 * and upserts them into gold.fhir_resources with the findings in {@code compliance_notes}.
 *
 * <p>Validation records, it doesn't gate: a non-compliant resource still lands in Gold, flagged
 * {@code is_us_core_compliant = false} with the reasons attached — dropping it would hide the
 * data-quality problem from the dashboard instead of surfacing it.
 *
 * <p>Each patient's resources are written in one transaction (all or nothing per patient); a
 * build-all run uses a transaction per patient so one bad patient can't block the rest.
 */
@Service
public class SilverFhirBuildService {

    private static final Logger log = LoggerFactory.getLogger(SilverFhirBuildService.class);
    static final String VALIDATOR_NAME = "HL7 FHIR Validator (HAPI FHIR 8.2.0) / US Core 6.1.0";

    private final SilverClinicalRepository silverRepository;
    private final PatientResourceBuilder patientBuilder;
    private final ConditionResourceBuilder conditionBuilder;
    private final ObservationResourceBuilder observationBuilder;
    private final MedicationRequestResourceBuilder medicationBuilder;
    private final UsCoreFhirValidator validator;
    private final GoldFhirResourceRepository goldRepository;
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
                                  GoldFhirResourceRepository goldRepository,
                                  FhirContext fhirContext,
                                  ObjectMapper objectMapper,
                                  TransactionTemplate transactionTemplate) {
        this(silverRepository, patientBuilder, conditionBuilder, observationBuilder, medicationBuilder, validator,
                goldRepository, fhirContext, objectMapper, transactionTemplate, Clock.systemUTC());
    }

    SilverFhirBuildService(SilverClinicalRepository silverRepository,
                           PatientResourceBuilder patientBuilder,
                           ConditionResourceBuilder conditionBuilder,
                           ObservationResourceBuilder observationBuilder,
                           MedicationRequestResourceBuilder medicationBuilder,
                           UsCoreFhirValidator validator,
                           GoldFhirResourceRepository goldRepository,
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
        this.goldRepository = goldRepository;
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

    private SilverPatientBuildResult buildAndPersist(SilverPatientRow patientRow) {
        String patientFhirId = FhirIds.patient(patientRow.id());

        List<Resource> resources = new ArrayList<>();
        resources.add(patientBuilder.build(patientRow));
        silverRepository.findConditions(patientRow.id())
                .forEach(c -> resources.add(conditionBuilder.build(c, patientFhirId)));
        silverRepository.findObservations(patientRow.id())
                .forEach(o -> resources.add(observationBuilder.build(o, patientFhirId)));
        silverRepository.findMedications(patientRow.id())
                .forEach(m -> resources.add(medicationBuilder.build(m, patientFhirId)));

        List<BuiltResourceSummary> summaries = resources.stream().map(this::validateAndPersist).toList();
        int compliant = (int) summaries.stream().filter(BuiltResourceSummary::usCoreCompliant).count();
        log.info("Silver patient {} → {} FHIR resource(s), {} US Core compliant",
                patientRow.id(), summaries.size(), compliant);
        return new SilverPatientBuildResult(patientRow.id(), patientFhirId, summaries.size(), compliant, summaries);
    }

    private BuiltResourceSummary validateAndPersist(Resource resource) {
        String json = fhirContext.newJsonParser().encodeResourceToString(resource);
        FhirValidationOutcome outcome = validator.validateJson(json);
        String resourceType = resource.fhirType();
        String fhirId = resource.getIdElement().getIdPart();

        long goldId = goldRepository.upsert(new GoldFhirResourceRecord(
                resourceType, fhirId, json, outcome.compliant(), complianceNotesJson(outcome)));

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

    private String complianceNotesJson(FhirValidationOutcome outcome) {
        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("validator", VALIDATOR_NAME);
        notes.put("profile", outcome.profile());
        notes.put("validatedAt", Instant.now(clock).toString());
        notes.put("compliant", outcome.compliant());
        notes.put("errorCount", outcome.errorCount());
        notes.put("warningCount", outcome.warningCount());
        notes.put("violations", outcome.violations());
        try {
            return objectMapper.writeValueAsString(notes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize compliance notes for "
                    + outcome.resourceType() + "/" + outcome.resourceId(), e);
        }
    }
}
