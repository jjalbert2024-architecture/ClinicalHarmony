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

class RxNormValidatorTest {

    private JdbcTemplate jdbcTemplate;
    private RxNormValidator validator;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        validator = new RxNormValidator(jdbcTemplate);
    }

    @Test
    void validCodeReturnsNameAndTermType() {
        when(jdbcTemplate.queryForList(anyString(), eq("6809"))).thenReturn(List.of(
                Map.of("name", "Metformin", "term_type", "IN")
        ));

        ValidationOutcome outcome = validator.validate("6809");

        assertThat(outcome.valid()).isTrue();
        assertThat(outcome.display()).isEqualTo("Metformin");
        assertThat(outcome.category()).isEqualTo("IN");
    }

    @Test
    void unknownCodeIsInvalid() {
        when(jdbcTemplate.queryForList(anyString(), eq("999999"))).thenReturn(List.of());

        ValidationOutcome outcome = validator.validate("999999");

        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.reason()).contains("not found");
    }

    @Test
    void nullCodeIsHandledWithoutHittingTheDatabase() {
        ValidationOutcome outcome = validator.validate(null);

        assertThat(outcome.valid()).isFalse();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void blankCodeIsHandledWithoutHittingTheDatabase() {
        ValidationOutcome outcome = validator.validate("  ");

        assertThat(outcome.valid()).isFalse();
        verifyNoInteractions(jdbcTemplate);
    }
}
