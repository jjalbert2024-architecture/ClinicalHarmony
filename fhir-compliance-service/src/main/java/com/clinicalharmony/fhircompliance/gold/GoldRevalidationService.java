package com.clinicalharmony.fhircompliance.gold;

import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRepository;
import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRow;
import com.clinicalharmony.fhircompliance.service.silver.BuiltResourceSummary;
import com.clinicalharmony.fhircompliance.validation.FhirValidationOutcome;
import com.clinicalharmony.fhircompliance.validation.UsCoreFhirValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Validates Gold rows that were seeded straight into Gold (migration 19's CMS-0057 resources) and
 * so have never been through the HL7 validator — compliance_notes {@code {"status":
 * "PENDING_VALIDATION"}}. Each is validated against its declared (or default) profile and
 * re-written through {@link GoldWriter}: real compliance_notes, a real is_us_core_compliant, a
 * link to its patient's master if one exists, and a SEED → GOLD lineage row.
 *
 * <p>Runs on startup ({@link GoldRevalidationRunner}) and on demand via
 * {@code POST /api/fhir/gold/revalidate}. Idempotent: once validated a row is no longer pending.
 * One transaction per row, so one bad row can't hold the others back.
 */
@Service
public class GoldRevalidationService {

    private static final Logger log = LoggerFactory.getLogger(GoldRevalidationService.class);

    private final GoldFhirResourceRepository fhirResources;
    private final UsCoreFhirValidator validator;
    private final GoldWriter goldWriter;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public GoldRevalidationService(GoldFhirResourceRepository fhirResources, UsCoreFhirValidator validator,
                                   GoldWriter goldWriter, ObjectMapper objectMapper,
                                   TransactionTemplate transactionTemplate) {
        this(fhirResources, validator, goldWriter, objectMapper, transactionTemplate, Clock.systemUTC());
    }

    GoldRevalidationService(GoldFhirResourceRepository fhirResources, UsCoreFhirValidator validator,
                            GoldWriter goldWriter, ObjectMapper objectMapper,
                            TransactionTemplate transactionTemplate, Clock clock) {
        this.fhirResources = fhirResources;
        this.validator = validator;
        this.goldWriter = goldWriter;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public List<BuiltResourceSummary> revalidatePending() {
        List<GoldFhirResourceRow> pending = fhirResources.findPendingValidation();
        if (pending.isEmpty()) {
            return List.of();
        }
        log.info("Validating {} Gold resource(s) pending validation", pending.size());
        List<BuiltResourceSummary> results = new ArrayList<>(pending.size());
        for (GoldFhirResourceRow row : pending) {
            results.add(transactionTemplate.execute(status -> revalidate(row)));
        }
        long compliant = results.stream().filter(BuiltResourceSummary::usCoreCompliant).count();
        log.info("Validated {} pending Gold resource(s): {} compliant, {} not", results.size(), compliant,
                results.size() - compliant);
        return results;
    }

    private BuiltResourceSummary revalidate(GoldFhirResourceRow row) {
        FhirValidationOutcome outcome = validator.validateJson(row.resourceJson());
        String seedFile = seedFile(row.complianceNotes());
        Long masterId = patientReference(row.resourceJson())
                .flatMap(fhirResources::findMasterPatientIdForPatient)
                .orElse(row.masterPatientId());

        LineageStep seedStep = new LineageStep(LineageStep.SEED,
                row.createdAt() == null ? null : row.createdAt().toInstant().toString(),
                "docs/db-init/" + seedFile, null, null, null);
        long goldId = goldWriter.writeResource(new GoldResourceWrite(
                row.resourceType(), row.fhirId(), row.resourceJson(), outcome.compliant(),
                ComplianceNotes.toJson(outcome, Instant.now(clock), objectMapper),
                masterId,
                new LineageSource("seed", seedFile, null, LineageSource.SEED_LOAD),
                List.of(seedStep),
                "fhir-compliance-service GoldRevalidationService + HL7 validator (" + outcome.profile() + ")",
                Hashes.sha256Hex(row.resourceJson())));

        if (!outcome.compliant()) {
            log.warn("Seeded {}/{} is NOT compliant with {} ({} error(s))", row.resourceType(), row.fhirId(),
                    outcome.profile(), outcome.errorCount());
        }
        return new BuiltResourceSummary(row.resourceType(), row.fhirId(), goldId, outcome.compliant(),
                outcome.errorCount(), outcome.warningCount(),
                outcome.violations().stream().filter(v -> "ERROR".equals(v.severity())).toList());
    }

    /** The FHIR id of the Patient this resource is about (subject / patient / beneficiary), if any. */
    Optional<String> patientReference(String resourceJson) {
        JsonNode root = readTree(resourceJson);
        for (String field : List.of("subject", "patient", "beneficiary")) {
            String reference = root.path(field).path("reference").asText("");
            if (reference.startsWith("Patient/") && reference.length() > "Patient/".length()) {
                return Optional.of(reference.substring("Patient/".length()));
            }
        }
        return Optional.empty();
    }

    private String seedFile(String complianceNotes) {
        String seed = complianceNotes == null ? "" : readTree(complianceNotes).path("seed").asText("");
        return seed.isBlank() ? "unknown-seed" : seed;
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored Gold JSON is unreadable", e);
        }
    }
}
