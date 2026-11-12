package com.clinicalharmony.fhircompliance.cms0057;

import com.clinicalharmony.fhircompliance.cms0057.Cms0057SearchParameters.ParsedSearch;
import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRepository.SqlCriterion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Cms0057SearchParametersTest {

    private final Cms0057SearchParameters params = new Cms0057SearchParameters(new ObjectMapper());

    private static MultiValueMap<String, String> query(String... pairs) {
        MultiValueMap<String, String> q = new LinkedMultiValueMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            q.add(pairs[i], pairs[i + 1]);
        }
        return q;
    }

    @Test
    void bareIdPatientBecomesTypedReferenceContainment() {
        ParsedSearch parsed = params.parse("Coverage", query("patient", "silver-patient-1"));
        assertThat(parsed.criteria()).singleElement().satisfies(c -> {
            assertThat(c.sql()).isEqualTo("resource_json @> ?::jsonb");
            assertThat(c.args()).containsExactly("{\"beneficiary\":{\"reference\":\"Patient/silver-patient-1\"}}");
        });
        assertThat(parsed.count()).isEqualTo(50);
    }

    @Test
    void typedReferenceIsAcceptedAsIs() {
        SqlCriterion c = params.parse("Device", query("patient", "Patient/silver-patient-2")).criteria().get(0);
        assertThat(c.args()).containsExactly("{\"patient\":{\"reference\":\"Patient/silver-patient-2\"}}");
    }

    @Test
    void referenceArrayParameterMatchesAnyElement() {
        SqlCriterion c = params.parse("Coverage", query("payor", "Organization/cms-org-payer")).criteria().get(0);
        assertThat(c.args()).containsExactly("{\"payor\":[{\"reference\":\"Organization/cms-org-payer\"}]}");
    }

    @Test
    void tokenWithSystemAndCode() {
        SqlCriterion c = params.parse("MedicationKnowledge",
                query("code", "http://www.nlm.nih.gov/research/umls/rxnorm|861007")).criteria().get(0);
        assertThat(c.args()).containsExactly(
                "{\"code\":{\"coding\":[{\"system\":\"http://www.nlm.nih.gov/research/umls/rxnorm\",\"code\":\"861007\"}]}}");
    }

    @Test
    void tokenCodeOnlyAndSystemOnly() {
        assertThat(params.parse("Device", query("type", "14106009")).criteria().get(0).args())
                .containsExactly("{\"type\":{\"coding\":[{\"code\":\"14106009\"}]}}");
        assertThat(params.parse("Device", query("type", "http://snomed.info/sct|")).criteria().get(0).args())
                .containsExactly("{\"type\":{\"coding\":[{\"system\":\"http://snomed.info/sct\"}]}}");
    }

    @Test
    void commaMeansOrAndRepeatingMeansAnd() {
        ParsedSearch parsed = params.parse("Coverage",
                query("status", "active,cancelled", "patient", "silver-patient-1"));
        assertThat(parsed.criteria()).hasSize(2);
        SqlCriterion or = parsed.criteria().get(0);
        assertThat(or.sql()).isEqualTo("(resource_json @> ?::jsonb) OR (resource_json @> ?::jsonb)");
        assertThat(or.args()).containsExactly("{\"status\":\"active\"}", "{\"status\":\"cancelled\"}");
    }

    @Test
    void booleanAndIdParameters() {
        assertThat(params.parse("PractitionerRole", query("active", "true")).criteria().get(0).args())
                .containsExactly("{\"active\":true}");
        SqlCriterion id = params.parse("PractitionerRole", query("_id", "cms-practitionerrole-1")).criteria().get(0);
        assertThat(id.sql()).isEqualTo("fhir_id = ?");
        assertThat(id.args()).containsExactly("cms-practitionerrole-1");
    }

    @Test
    void unversionedCanonicalMatchesAnyVersionVersionedMatchesExactly() {
        SqlCriterion any = params.parse("MeasureReport", query("measure", "http://x.org/Measure/A_1")).criteria().get(0);
        assertThat(any.sql()).contains("LIKE");
        assertThat(any.args()).containsExactly("http://x.org/Measure/A_1", "http://x.org/Measure/A\\_1|%");
        SqlCriterion exact = params.parse("MeasureReport", query("measure", "http://x.org/Measure/A|1.0")).criteria().get(0);
        assertThat(exact.sql()).doesNotContain("LIKE");
        assertThat(exact.args()).containsExactly("http://x.org/Measure/A|1.0");
    }

    @Test
    void countAndFormatAreControlParametersNotCriteria() {
        ParsedSearch parsed = params.parse("Device", query("_count", "5", "_format", "json"));
        assertThat(parsed.criteria()).isEmpty();
        assertThat(parsed.count()).isEqualTo(5);
    }

    @ParameterizedTest(name = "{0}?{1}={2} → 400")
    @CsvSource(delimiter = ';', value = {
            "Coverage;foo;bar;Unknown search parameter 'foo'",
            "Coverage;status:not;active;modifiers and chaining are not supported",
            "Coverage;patient.name;x;modifiers and chaining are not supported",
            "Coverage;patient;Practitioner/1;references Patient",
            "Coverage;payor;cms-org-payer;needs a typed reference",
            "Coverage;patient;Patient/a/b;relative reference like 'Type/id'",
            "PractitionerRole;active;yes;must be true or false",
            "Device;_count;500;_count must be between 0 and 100",
            "Device;_count;abc;_count must be an integer",
            "Device;_format;xml;Unsupported _format",
            "Device;status;a b;must be a code",
            "Device;status;active,;empty value",
            "Device;_id;bad id!;must be a FHIR id",
            "MedicationKnowledge;code;|;token is empty"
    })
    void invalidSearchesAre400(String type, String name, String value, String message) {
        assertThatThrownBy(() -> params.parse(type, query(name, value)))
                .isInstanceOfSatisfying(FhirApiException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(400);
                    assertThat(e.issueCode()).isEqualTo("invalid");
                })
                .hasMessageContaining(message);
    }

    @Test
    void blankValueIs400() {
        assertThatThrownBy(() -> params.parse("Device", query("status", "")))
                .isInstanceOf(FhirApiException.class).hasMessageContaining("has no value");
    }

    @Test
    void unsupportedTypeIs404() {
        assertThatThrownBy(() -> params.parse("Patient", query()))
                .isInstanceOfSatisfying(FhirApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(404))
                .hasMessageContaining("not supported");
    }

    @Test
    void everyCms0057TypeIsSupported() {
        assertThat(Cms0057SearchParameters.SUPPORTED_TYPES).allMatch(params::supports);
        assertThat(params.parameterNames("Coverage")).contains("_id", "patient", "beneficiary", "payor", "status", "type");
        assertThat(List.of("Patient", "Organization")).noneMatch(params::supports);
    }
}
