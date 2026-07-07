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

class LoincValidatorTest {

    private JdbcTemplate jdbcTemplate;
    private LoincValidator validator;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        validator = new LoincValidator(jdbcTemplate);
    }

    @Test
    void validCodeReturnsLongCommonNameAndScaleType() {
        when(jdbcTemplate.queryForList(anyString(), eq("2093-3"))).thenReturn(List.of(
                Map.of("long_common_name", "Cholesterol [Mass/volume] in Serum or Plasma", "scale_type", "Qn")
        ));

        ValidationOutcome outcome = validator.validate("2093-3");

        assertThat(outcome.valid()).isTrue();
        assertThat(outcome.display()).isEqualTo("Cholesterol [Mass/volume] in Serum or Plasma");
        assertThat(outcome.category()).isEqualTo("Qn");
    }

    @Test
    void unknownCodeIsInvalid() {
        when(jdbcTemplate.queryForList(anyString(), eq("99999-9"))).thenReturn(List.of());

        ValidationOutcome outcome = validator.validate("99999-9");

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
        ValidationOutcome outcome = validator.validate("");

        assertThat(outcome.valid()).isFalse();
        verifyNoInteractions(jdbcTemplate);
    }
}
