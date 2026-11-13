package com.clinicalharmony.gateway.downstream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleOwnershipTest {

    @Test
    void routesEachRuleFamilyToTheServiceThatEvaluatesIt() {
        assertThat(RuleOwnership.evaluatedBy("LAB_REFERENCE_RANGE")).isEqualTo(Downstream.RULES_ENGINE);
        assertThat(RuleOwnership.evaluatedBy("FHIR_USCORE_COMPLETENESS")).isEqualTo(Downstream.RULES_ENGINE);
        assertThat(RuleOwnership.evaluatedBy("USCORE_PATIENT_COMPLETENESS")).isEqualTo(Downstream.FHIR_COMPLIANCE);
        assertThat(RuleOwnership.evaluatedBy("CODE-004")).isEqualTo(Downstream.ONTOLOGY);
        assertThat(RuleOwnership.evaluatedBy("PMI_MATCH")).isEqualTo(Downstream.PATIENT_INDEX);
        assertThat(RuleOwnership.evaluatedBy("QUAL-001")).isNull();
    }

    @Test
    void onlyServicesWithAToggleEndpointAreToggleable() {
        assertThat(RuleOwnership.toggleable("GENDER_PREGNANCY_CONFLICT")).isTrue();
        assertThat(RuleOwnership.toggleable("USCORE_ENCOUNTER_COMPLETENESS")).isTrue();
        assertThat(RuleOwnership.toggleable("CODE-001")).isFalse();
        assertThat(RuleOwnership.toggleable("PMI_MATCH")).isFalse();
        assertThat(RuleOwnership.toggleable("IDENT-001")).isFalse();
        assertThat(RuleOwnership.toggleUri("LAB_REFERENCE_RANGE")).isEqualTo("/api/rules/{code}/toggle");
        assertThat(RuleOwnership.toggleUri("USCORE_CONDITION_COMPLETENESS"))
                .isEqualTo("/api/fhir-compliance/rules/{code}/toggle");
        assertThatThrownBy(() -> RuleOwnership.toggleUri("CODE-001")).isInstanceOf(IllegalArgumentException.class);
    }
}
