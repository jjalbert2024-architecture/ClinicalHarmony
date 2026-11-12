package com.clinicalharmony.fhircompliance.service;

import com.clinicalharmony.fhircompliance.gold.GoldResourceWrite;
import com.clinicalharmony.fhircompliance.gold.GoldWriter;
import com.clinicalharmony.fhircompliance.gold.Hashes;
import com.clinicalharmony.fhircompliance.gold.LineageSource;
import com.clinicalharmony.fhircompliance.gold.LineageStep;
import com.clinicalharmony.fhircompliance.web.dto.PatientRecordRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Builds a FHIR Bundle via FhirBundleGenerator, then persists each entry to gold.fhir_resources
 * through {@link GoldWriter} (the single owner of Gold writes). Each resource is cross-checked
 * with UsCoreValidationService.checkResource() before persisting rather than assuming the generator
 * is correct by construction, so is_us_core_compliant reflects an actual check, not an assumption.
 *
 * <p>The input is a caller-supplied record, not a Silver row, so lineage is API → GOLD (source
 * {@code api}/{@code POST /api/fhir-compliance/generate}, no source id) and no master patient is
 * linked — there's no Silver patient to anchor one to.
 */
@Service
public class FhirGenerationService {

    private static final Logger log = LoggerFactory.getLogger(FhirGenerationService.class);

    static final String API_SOURCE_TABLE = "POST /api/fhir-compliance/generate";
    static final String GOLD_TRANSFORMER =
            "fhir-compliance-service FhirBundleGenerator + UsCoreValidationService (structural required-field check)";

    private final FhirBundleGenerator bundleGenerator;
    private final UsCoreValidationService validationService;
    private final GoldWriter goldWriter;
    private final ObjectMapper objectMapper;

    public FhirGenerationService(FhirBundleGenerator bundleGenerator, UsCoreValidationService validationService,
                                  GoldWriter goldWriter, ObjectMapper objectMapper) {
        this.bundleGenerator = bundleGenerator;
        this.validationService = validationService;
        this.goldWriter = goldWriter;
        this.objectMapper = objectMapper;
    }

    /** One transaction for the whole Bundle: every entry (and its lineage) lands, or none does. */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> generateAndPersist(PatientRecordRequest request) {
        Map<String, Object> bundle = bundleGenerator.generateBundle(request);
        String requestHash = Hashes.sha256Hex(String.valueOf(request));
        LineageStep apiStep = new LineageStep(LineageStep.API, Instant.now().toString(),
                "caller-supplied PatientRecordRequest (patientId=" + request.patientId() + ")", requestHash, null, null);
        List<Object> entries = (List<Object>) bundle.get("entry");
        for (Object entryObj : entries) {
            Map<String, Object> entry = (Map<String, Object>) entryObj;
            persistResource((Map<String, Object>) entry.get("resource"), apiStep, requestHash);
        }
        return bundle;
    }

    private void persistResource(Map<String, Object> resource, LineageStep apiStep, String requestHash) {
        String resourceType = String.valueOf(resource.get("resourceType"));
        String fhirId = String.valueOf(resource.get("id"));
        ResourceValidationResult checkResult = validationService.checkResource(resource);
        boolean compliant = checkResult == null || checkResult.valid();

        try {
            String json = objectMapper.writeValueAsString(resource);
            long id = goldWriter.writeResource(new GoldResourceWrite(resourceType, fhirId, json, compliant, null, null,
                    new LineageSource("api", API_SOURCE_TABLE, null, LineageSource.API_GENERATE),
                    List.of(apiStep), GOLD_TRANSFORMER, requestHash));
            log.info("Persisted gold.fhir_resources id={} resourceType={} fhirId={} compliant={}",
                    id, resourceType, fhirId, compliant);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize generated FHIR resource " + resourceType + "/" + fhirId, e);
        }
    }
}
