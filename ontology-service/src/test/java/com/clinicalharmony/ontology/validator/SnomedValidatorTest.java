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

class SnomedValidatorTest {

    private JdbcTemplate jdbcTemplate;
    private SnomedValidator validator;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        validator = new SnomedValidator(jdbcTemplate);
    }

    @Test
    void validCodeReturnsTermAndSemanticTag() {
        when(jdbcTemplate.queryForList(anyString(), eq("44054006"))).thenReturn(List.of(
                Map.of("term", "Diabetes mellitus type 2", "semantic_tag", "disorder")
        ));

        ValidationOutcome outcome = validator.validate("44054006");

        assertThat(outcome.valid()).isTrue();
        assertThat(outcome.display()).isEqualTo("Diabetes mellitus type 2");
        assertThat(outcome.category()).isEqualTo("disorder");
    }

    @Test
    void unknownCodeIsInvalid() {
        when(jdbcTemplate.queryForList(anyString(), eq("000000"))).thenReturn(List.of());

        ValidationOutcome outcome = validator.validate("000000");

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
