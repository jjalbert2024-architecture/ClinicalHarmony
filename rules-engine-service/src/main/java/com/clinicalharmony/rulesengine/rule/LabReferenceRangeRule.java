package com.clinicalharmony.rulesengine.rule;

import com.clinicalharmony.rulesengine.service.ClinicalRule;
import com.clinicalharmony.rulesengine.service.RuleResult;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LAB_REFERENCE_RANGE: an observation's numeric value must fall within its reference range.
 * The range comes from the observation item itself when the caller supplies one (e.g. parsed
 * from an HL7 OBX-7 segment), falling back to the rule's built-in defaultRanges keyed by LOINC
 * code. Observations with neither source of a range, or without a numeric value, aren't
 * evaluable and are skipped.
 */
@Component
public class LabReferenceRangeRule implements RuleEvaluator {

    @Override
    public String ruleCode() {
        return "LAB_REFERENCE_RANGE";
    }

    @Override
    public List<RuleResult> evaluate(PatientRecordRequest request, ClinicalRule rule) {
        List<RuleResult> results = new ArrayList<>();
        JsonNode defaultRanges = rule.expression().path("defaultRanges");

        if (request.observations() != null) {
            for (PatientRecordRequest.ObservationItem observation : request.observations()) {
                if (observation.valueNumeric() == null) {
                    continue;
                }
                double[] range = resolveRange(observation, defaultRanges);
                if (range == null) {
                    continue;
                }
                double low = range[0];
                double high = range[1];
                double value = observation.valueNumeric();
                if (value < low || value > high) {
                    results.add(RuleResult.fail(ruleCode(), rule.severity(), "OBSERVATION", observation.id(), request.patientId(),
                            "observations[].valueNumeric",
                            "Value " + value + " is outside reference range [" + low + ", " + high + "] for " + observation.code(),
                            String.valueOf(value), "Verify result; expected range [" + low + ", " + high + "]"));
                } else {
                    results.add(RuleResult.pass(ruleCode(), rule.severity(), "OBSERVATION", observation.id(), request.patientId()));
                }
            }
        }

        if (results.isEmpty()) {
            return List.of(RuleResult.pass(ruleCode(), rule.severity(), "PATIENT", request.patientId(), request.patientId()));
        }
        return results;
    }

    private static double[] resolveRange(PatientRecordRequest.ObservationItem observation, JsonNode defaultRanges) {
        if (observation.referenceLow() != null && observation.referenceHigh() != null) {
            return new double[] {observation.referenceLow(), observation.referenceHigh()};
        }
        if (observation.code() == null) {
            return null;
        }
        JsonNode configured = defaultRanges.path(observation.code());
        if (configured.isMissingNode() || configured.isNull()) {
            return null;
        }
        JsonNode low = configured.path("low");
        JsonNode high = configured.path("high");
        if (low.isMissingNode() || high.isMissingNode()) {
            return null;
        }
        return new double[] {low.asDouble(), high.asDouble()};
    }
}
