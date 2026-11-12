package com.clinicalharmony.fhircompliance.security;

import com.clinicalharmony.fhircompliance.security.SmartScopes.Interaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SmartScopesTest {

    @ParameterizedTest(name = "{0} on {1} {2} -> {3}")
    @CsvSource({
            // v2: per-interaction letters
            "system/Coverage.rs,        Coverage, READ,   true",
            "system/Coverage.rs,        Coverage, SEARCH, true",
            "system/Coverage.r,         Coverage, READ,   true",
            "system/Coverage.r,         Coverage, SEARCH, false",
            "system/Coverage.s,         Coverage, SEARCH, true",
            "system/Coverage.s,         Coverage, READ,   false",
            "system/Coverage.cruds,     Coverage, READ,   true",
            "system/Coverage.cu,        Coverage, READ,   false",
            // v2 letters must be in c-r-u-d-s order
            "system/Coverage.sr,        Coverage, READ,   false",
            // wrong type / wildcard type
            "system/Device.rs,          Coverage, READ,   false",
            "system/*.rs,               Coverage, READ,   true",
            "system/*.s,                MeasureReport, SEARCH, true",
            // v1 compatibility
            "system/Coverage.read,      Coverage, SEARCH, true",
            "system/Coverage.*,         Coverage, READ,   true",
            "system/Coverage.write,     Coverage, READ,   false",
            // only the system/ context applies to backend services
            "patient/Coverage.rs,       Coverage, READ,   false",
            "user/Coverage.rs,          Coverage, READ,   false",
            // garbage and the operational scope
            "clinicalharmony/pipeline.write, Coverage, READ, false",
            "system/Coverage,           Coverage, READ,   false",
            "openid,                    Coverage, READ,   false"
    })
    void evaluatesSingleScope(String scope, String type, Interaction interaction, boolean expected) {
        assertThat(SmartScopes.permits(List.of(scope), type, interaction)).isEqualTo(expected);
    }

    @Test
    void anyMatchingScopeInTheListGrants() {
        assertThat(SmartScopes.permits(List.of("openid", "system/Device.r", "system/Coverage.s"),
                "Coverage", Interaction.SEARCH)).isTrue();
        assertThat(SmartScopes.permits(List.of(), "Coverage", Interaction.SEARCH)).isFalse();
    }

    @Test
    void requiredScopeIsTheNarrowestV2Scope() {
        assertThat(SmartScopes.required("Device", Interaction.READ)).isEqualTo("system/Device.r");
        assertThat(SmartScopes.required("Device", Interaction.SEARCH)).isEqualTo("system/Device.s");
    }

    @Test
    void supportedListsEveryCms0057TypeTheWildcardAndThePipelineScope() {
        assertThat(SmartScopes.supported()).containsExactly(
                "system/Coverage.rs", "system/PractitionerRole.rs", "system/MedicationKnowledge.rs",
                "system/MeasureReport.rs", "system/Device.rs", "system/*.rs", "clinicalharmony/pipeline.write");
    }
}
