package com.clinicalharmony.rulesengine.rule;

import com.clinicalharmony.rulesengine.service.ClinicalRule;
import com.clinicalharmony.rulesengine.service.RuleResult;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FHIR_USCORE_COMPLETENESS: an incoming FHIR resource must carry the required fields for its
 * resourceType (configured per-type in rule_expression.requiredFields). This is a cheap
 * presence check on Silver-side data, not full US Core IG conformance (cardinality, terminology
 * bindings, invariants) — that remains fhir-compliance-service's job in Phase 5.
 *
 * <p>FHIR resource ids are strings (often UUIDs), not the BIGINT silver.validation_reports.entity_id
 * expects, so findings against a FHIR resource are recorded against the patient (entityId =
 * patientId) with the resource's own id captured in the failure message instead.
 */
@Component
public class FhirUsCoreCompletenessRule implements RuleEvaluator {

    @Override
    public String ruleCode() {
        return "FHIR_USCORE_COMPLETENESS";
    }

    @Override
    public List<RuleResult> evaluate(PatientRecordRequest request, ClinicalRule rule) {
        List<RuleResult> results = new ArrayList<>();
        JsonNode requiredFieldsByType = rule.expression().path("requiredFields");

        if (request.fhirResources() != null) {
            for (Map<String, Object> resource : request.fhirResources()) {
                Object resourceType = resource.get("resourceType");
                if (resourceType == null) {
                    continue;
                }
                String entityType = mapEntityType(resourceType.toString());
                JsonNode requiredFields = requiredFieldsByType.path(resourceType.toString());
                if (entityType == null || requiredFields.isMissingNode() || !requiredFields.isArray()) {
                    continue;
                }

                List<String> missing = new ArrayList<>();
                for (JsonNode fieldNode : requiredFields) {
                    String field = fieldNode.asText();
                    Object value = resource.get(field);
                    if (value == null || (value instanceof String s && s.isBlank())) {
                        missing.add(field);
                    }
                }

                Object resourceId = resource.get("id");
                if (missing.isEmpty()) {
                    results.add(RuleResult.pass(ruleCode(), rule.severity(), entityType, request.patientId(), request.patientId()));
                } else {
                    results.add(RuleResult.fail(ruleCode(), rule.severity(), entityType, request.patientId(), request.patientId(),
                            String.join(",", missing),
                            resourceType + "/" + resourceId + " is missing required field(s): " + String.join(", ", missing),
                            String.join(",", missing), "Populate the missing required field(s) before FHIR export"));
                }
            }
        }

        if (results.isEmpty()) {
            return List.of(RuleResult.pass(ruleCode(), rule.severity(), "PATIENT", request.patientId(), request.patientId()));
        }
        return results;
    }

    private static String mapEntityType(String resourceType) {
        return switch (resourceType) {
            case "Patient" -> "PATIENT";
            case "Observation" -> "OBSERVATION";
            case "Condition" -> "CONDITION";
            default -> null;
        };
    }
}
