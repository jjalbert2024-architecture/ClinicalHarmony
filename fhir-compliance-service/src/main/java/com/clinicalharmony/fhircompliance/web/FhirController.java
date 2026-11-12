package com.clinicalharmony.fhircompliance.web;

import com.clinicalharmony.fhircompliance.gold.GoldRevalidationService;
import com.clinicalharmony.fhircompliance.service.silver.BuiltResourceSummary;
import com.clinicalharmony.fhircompliance.service.silver.SilverFhirBuildService;
import com.clinicalharmony.fhircompliance.service.silver.SilverPatientBuildResult;
import com.clinicalharmony.fhircompliance.validation.FhirValidationOutcome;
import com.clinicalharmony.fhircompliance.validation.UsCoreFhirValidator;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 5b endpoints, backed by the official HL7 validator against US Core 6.1.0 (the
 * {@code /api/fhir-compliance/*} endpoints keep the Phase 5 hand-written structural check).
 *
 * <ul>
 *   <li>{@code POST /api/fhir/validate} — debugger: any single FHIR R4 resource in, every
 *       violation out (severity, FHIRPath location, message, rule, suggested fix). Always 200 for
 *       a parseable resource, compliant or not; 400 only if it isn't a FHIR resource at all.</li>
 *   <li>{@code POST /api/fhir/build/patients/{silverPatientId}} — build + validate + persist one
 *       Silver patient's resources into gold.fhir_resources.</li>
 *   <li>{@code POST /api/fhir/build} — same, for every Silver patient.</li>
 *   <li>{@code POST /api/fhir/gold/revalidate} — validate Gold rows seeded straight into Gold that no
 *       validator has checked yet (migration 19's CMS-0057 resources); also runs on startup.</li>
 * </ul>
 *
 * <p>All Gold writes go through GoldWriter (master patient, resource, lineage).
 */
@RestController
@RequestMapping("/api/fhir")
public class FhirController {

    private static final String FHIR_JSON = "application/fhir+json";

    private final UsCoreFhirValidator validator;
    private final SilverFhirBuildService buildService;
    private final GoldRevalidationService revalidationService;

    public FhirController(UsCoreFhirValidator validator, SilverFhirBuildService buildService,
                          GoldRevalidationService revalidationService) {
        this.validator = validator;
        this.buildService = buildService;
        this.revalidationService = revalidationService;
    }

    @PostMapping(value = "/validate", consumes = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FhirValidationOutcome> validate(@RequestBody String resourceJson) {
        return ResponseEntity.ok(validator.validateJson(resourceJson));
    }

    @PostMapping(value = "/build/patients/{silverPatientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SilverPatientBuildResult> buildPatient(@PathVariable long silverPatientId) {
        return ResponseEntity.ok(buildService.buildPatient(silverPatientId));
    }

    @PostMapping(value = "/build", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<SilverPatientBuildResult>> buildAll() {
        return ResponseEntity.ok(buildService.buildAllPatients());
    }

    @PostMapping(value = "/gold/revalidate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<BuiltResourceSummary>> revalidatePendingGold() {
        return ResponseEntity.ok(revalidationService.revalidatePending());
    }
}
