package com.clinicalharmony.fhircompliance.service;

import com.clinicalharmony.fhircompliance.web.dto.FhirValidationRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Checks each submitted FHIR resource's required-field presence against the active
 * USCORE_*_COMPLETENESS profile for its resourceType. A resourceType with no active profile
 * (unsupported type, or its rule toggled off) is skipped, not failed — same "skip, don't fail"
 * convention rules-engine-service's RuleExecutor uses for rule codes it doesn't own.
 *
 * <p>This is structural presence checking only (required elements per US Core's base
 * cardinality) — no terminology binding or invariant validation. FHIR resource ids are strings,
 * not the BIGINT silver.validation_reports.entity_id expects, so findings are recorded against
 * the caller-supplied patientId, with the resource's own id captured in the failure message
 * (same convention as rules-engine-service's FHIR_USCORE_COMPLETENESS rule).
 *
 * <p>{@link #checkResource(Map)} is also reused by {@link FhirGenerationService} to verify
 * generated resources against this same logic before persisting them, rather than assuming the
 * generator is correct by construction.
 */
@Service
public class UsCoreValidationService {

    private final UsCoreRuleLoader ruleLoader;
    private final FhirValidationReportBuilder reportBuilder;

    public UsCoreValidationService(UsCoreRuleLoader ruleLoader, FhirValidationReportBuilder reportBuilder) {
        this.ruleLoader = ruleLoader;
        this.reportBuilder = reportBuilder;
    }

    public FhirValidationReport validate(FhirValidationRequest request) {
        List<ResourceValidationResult> results = request.fhirResources().stream()
                .map(this::checkResource)
                .filter(Objects::nonNull)
                .toList();

        List<Long> reportIds = reportBuilder.buildAndPersist(results, request.patientId());
        boolean allValid = results.stream().allMatch(ResourceValidationResult::valid);
        return new FhirValidationReport(request.patientId(), allValid, results, reportIds);
    }

    /**
     * Checks one resource against the active profile for its resourceType. Returns null (skip,
     * not fail) when the resource has no resourceType or no active profile applies to it.
     */
    public ResourceValidationResult checkResource(Map<String, Object> resource) {
        Object resourceTypeValue = resource.get("resourceType");
        if (resourceTypeValue == null) {
            return null;
        }
        String resourceType = resourceTypeValue.toString();
        UsCoreProfile profile = loadProfilesByResourceType().get(resourceType);
        if (profile == null) {
            return null;
        }

        String resourceId = String.valueOf(resource.getOrDefault("id", "unknown"));
        List<String> missing = new ArrayList<>();

        for (String field : profile.requiredFields()) {
            if (!isPresent(resource.get(field))) {
                missing.add(field);
            }
        }
        for (List<String> anyOfGroup : profile.requiredAnyOf()) {
            boolean satisfied = anyOfGroup.stream().anyMatch(field -> isPresent(resource.get(field)));
            if (!satisfied) {
                missing.add(String.join("|", anyOfGroup));
            }
        }

        if (missing.isEmpty()) {
            return ResourceValidationResult.pass(resourceType, resourceId, profile.ruleCode(), profile.severity());
        }
        String reason = resourceType + "/" + resourceId + " is missing required US Core field(s): " + String.join(", ", missing);
        return ResourceValidationResult.fail(resourceType, resourceId, profile.ruleCode(), profile.severity(), missing, reason);
    }

    private Map<String, UsCoreProfile> loadProfilesByResourceType() {
        return ruleLoader.loadActiveProfiles().stream()
                .collect(Collectors.toMap(UsCoreProfile::resourceType, Function.identity(), (a, b) -> a));
    }

    private static boolean isPresent(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String s) {
            return !s.isBlank();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }
}
