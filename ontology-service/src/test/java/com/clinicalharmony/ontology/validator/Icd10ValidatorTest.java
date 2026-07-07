package com.clinicalharmony.ontology.validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Icd10ValidatorTest {

    private JdbcTemplate jdbcTemplate;
    private Icd10Validator validator;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        validator = new Icd10Validator(jdbcTemplate);
    }

    @Test
    void validCodeReturnsDescriptionAndCategory() {
        when(jdbcTemplate.queryForList(anyString(), eq("E11.9"))).thenReturn(List.of(
                Map.of("description", "Type 2 diabetes mellitus without complications",
                        "category", "Endocrine, nutritional and metabolic diseases")
        ));

        ValidationOutcome outcome = validator.validate("E11.9");

        assertThat(outcome.valid()).isTrue();
        assertThat(outcome.code()).isEqualTo("E11.9");
        assertThat(outcome.display()).isEqualTo("Type 2 diabetes mellitus without complications");
        assertThat(outcome.category()).isEqualTo("Endocrine, nutritional and metabolic diseases");
    }

    @Test
    void unknownCodeIsInvalid() {
        when(jdbcTemplate.queryForList(anyString(), eq("Z99.99"))).thenReturn(List.of());

        ValidationOutcome outcome = validator.validate("Z99.99");

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.reason()).contains("not found");
    }

    @Test
    void nullCodeIsHandledWithoutHittingTheDatabase() {
        ValidationOutcome outcome = validator.validate(null);

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.reason()).contains("null or blank");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void blankCodeIsHandledWithoutHittingTheDatabase() {
        ValidationOutcome outcome = validator.validate("   ");

        assertThat(outcome.valid()).isFalse();
        verifyNoInteractions(jdbcTemplate);
    }
}
