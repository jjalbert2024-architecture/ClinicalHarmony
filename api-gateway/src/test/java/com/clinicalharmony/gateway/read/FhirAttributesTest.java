package com.clinicalharmony.gateway.read;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FhirAttributesTest {

    private static Map<String, String> attrs(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void everyKindBecomesABoundPredicate() {
        List<FhirAttributes.Condition> c = FhirAttributes.parse("Patient",
                attrs("gender", "female", "city", "Spring%", "phone", "no", "birthDateFrom", "1970", "birthDateTo", "1980-06"));

        assertThat(c).hasSize(5);
        assertThat(c.get(0).predicate()).endsWith("= :attr0");
        assertThat(c.get(0).value()).isEqualTo("female");
        assertThat(c.get(1).predicate()).endsWith("ILIKE :attr1");
        assertThat(c.get(1).value()).isEqualTo("%Spring\\%%");          // the user's % is escaped
        assertThat(c.get(2).predicate()).startsWith("NOT coalesce(");
        assertThat(c.get(2).param()).isNull();
        assertThat(c.get(3).predicate()).startsWith("left(").endsWith(", 4) >= :attr3");
        assertThat(c.get(4).predicate()).endsWith(", 7) <= :attr4");
        // the value is never part of the SQL text
        assertThat(c).extracting(FhirAttributes.Condition::predicate).noneMatch(p -> p.contains("female") || p.contains("1970"));
    }

    @Test
    void emptyValuesAreNoFilter() {
        assertThat(FhirAttributes.parse("Observation", attrs("status", " ", "interpretation", ""))).isEmpty();
        assertThat(FhirAttributes.parse(null, Map.of())).isEmpty();
    }

    @Test
    void refusesWhatItDoesNotKnow() {
        assertThatThrownBy(() -> FhirAttributes.parse(null, attrs("gender", "male")))
                .isInstanceOf(InvalidFilterException.class).hasMessageContaining("need a resource type");
        assertThatThrownBy(() -> FhirAttributes.parse("Observation", attrs("gender", "male")))
                .isInstanceOf(InvalidFilterException.class).hasMessageContaining("allowed: status, category");
        assertThatThrownBy(() -> FhirAttributes.parse("Patient", attrs("birthDate", "1970")))
                .hasMessageContaining("birthDateFrom / attr.birthDateTo");
        assertThatThrownBy(() -> FhirAttributes.parse("Patient", attrs("birthDateFrom", "70s")))
                .hasMessageContaining("YYYY, YYYY-MM or YYYY-MM-DD");
        assertThatThrownBy(() -> FhirAttributes.parse("Patient", attrs("phone", "maybe")))
                .hasMessageContaining("yes or no");
        assertThatThrownBy(() -> FhirAttributes.parse("Patient", attrs("genderFrom", "a")))
                .hasMessageContaining("Unknown attribute filter attr.genderFrom");
        assertThatThrownBy(() -> FhirAttributes.parse("Binary", attrs("status", "x")))
                .hasMessageContaining("(it has none)");
    }

    @Test
    void catalogCoversTheMainTypes() {
        assertThat(FhirAttributes.of("Patient")).extracting(FhirAttributes.Attribute::key)
                .containsExactly("gender", "birthDate", "state", "city", "postalCode", "phone");
        assertThat(FhirAttributes.of("Observation")).extracting(FhirAttributes.Attribute::key)
                .contains("status", "category", "interpretation", "abnormal", "effective");
        assertThat(FhirAttributes.of("Condition")).extracting(FhirAttributes.Attribute::key).contains("clinicalStatus", "onset");
        assertThat(FhirAttributes.of("MedicationRequest")).extracting(FhirAttributes.Attribute::key).contains("intent", "authoredOn");
        assertThat(FhirAttributes.of("AllergyIntolerance")).extracting(FhirAttributes.Attribute::key).contains("criticality");
        assertThat(FhirAttributes.of("Coverage")).extracting(FhirAttributes.Attribute::key).containsExactly("status");
        assertThat(FhirAttributes.of(null)).isEmpty();
    }
}
