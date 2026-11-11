package com.clinicalharmony.fhircompliance.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestedFixAdvisorTest {

    private final SuggestedFixAdvisor advisor = new SuggestedFixAdvisor();

    @Test
    void minimumCardinalityNamesTheMissingElement() {
        String fix = advisor.suggest("Validation_VAL_Profile_Minimum",
                "Patient.identifier: minimum required = 1, but only found 0 (from ...)", "Patient.identifier");
        assertThat(fix).isEqualTo("Add a 'identifier' element (Patient.identifier) — US Core requires it.");
    }

    @Test
    void minimumCardinalityAboveOne() {
        String fix = advisor.suggest(null, "X.y: minimum required = 2, but only found 1", "X.y");
        assertThat(fix).startsWith("Add at least 2 'y'");
    }

    @Test
    void maximumCardinality() {
        assertThat(advisor.suggest(null, "Patient.gender: max allowed = 1, but found 2", "Patient.gender"))
                .contains("at most 1");
    }

    @Test
    void knownRuleKeys() {
        assertThat(advisor.suggest("dom-6", "Constraint failed: dom-6", "Patient")).contains("narrative", "text");
        assertThat(advisor.suggest("Terminology_PassThrough_TX_Message", "CodeSystem is unknown", "Observation.code"))
                .contains("ontology-service");
    }

    @Test
    void usCoreInvariantFallback() {
        assertThat(advisor.suggest("us-core-99", "Constraint failed", "Patient")).contains("us-core-99");
    }

    @Test
    void genericTerminologyFallbackMentionsLocation() {
        assertThat(advisor.suggest("Terminology_Something_New", "msg", "Condition.code")).contains("Condition.code");
    }

    @Test
    void unknownRuleHasNoSuggestion() {
        assertThat(advisor.suggest("Something_Else", "msg", "X")).isNull();
        assertThat(advisor.suggest(null, null, null)).isNull();
    }
}
