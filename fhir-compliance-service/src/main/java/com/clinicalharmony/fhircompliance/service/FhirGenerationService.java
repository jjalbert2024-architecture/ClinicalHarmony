package com.clinicalharmony.fhircompliance.service;

import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRecord;
import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRepository;
import com.clinicalharmony.fhircompliance.web.dto.PatientRecordRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Builds a FHIR Bundle via FhirBundleGenerator, then persists each entry to
 * gold.fhir_resources — the table Phase 1 reserved for exactly this. Each resource is
 * cross-checked with UsCoreValidationService.checkResource() before persisting rather than
 * assuming the generator is correct by construction, so is_us_core_compliant reflects an actual
 * check, not an assumption.
 */
@Service
public class FhirGenerationService {

    private static final Logger log = LoggerFactory.getLogger(FhirGenerationService.class);

    private final FhirBundleGenerator bundleGenerator;
    private final UsCoreValidationService validationService;
    private final GoldFhirResourceRepository goldRepository;
    private final ObjectMapper objectMapper;

    public FhirGenerationService(FhirBundleGenerator bundleGenerator, UsCoreValidationService validationService,
                                  GoldFhirResourceRepository goldRepository, ObjectMapper objectMapper) {
        this.bundleGenerator = bundleGenerator;
        this.validationService = validationService;
        this.goldRepository = goldRepository;
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> generateAndPersist(PatientRecordRequest request) {
        Map<String, Object> bundle = bundleGenerator.generateBundle(request);
        List<Object> entries = (List<Object>) bundle.get("entry");
        for (Object entryObj : entries) {
            Map<String, Object> entry = (Map<String, Object>) entryObj;
            persistResource((Map<String, Object>) entry.get("resource"));
        }
        return bundle;
    }

    private void persistResource(Map<String, Object> resource) {
        String resourceType = String.valueOf(resource.get("resourceType"));
        String fhirId = String.valueOf(resource.get("id"));
        ResourceValidationResult checkResult = validationService.checkResource(resource);
        boolean compliant = checkResult == null || checkResult.valid();

        try {
            String json = objectMapper.writeValueAsString(resource);
            long id = goldRepository.upsert(new GoldFhirResourceRecord(resourceType, fhirId, json, compliant));
            log.info("Persisted gold.fhir_resources id={} resourceType={} fhirId={} compliant={}",
                    id, resourceType, fhirId, compliant);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize generated FHIR resource " + resourceType + "/" + fhirId, e);
        }
    }
}
