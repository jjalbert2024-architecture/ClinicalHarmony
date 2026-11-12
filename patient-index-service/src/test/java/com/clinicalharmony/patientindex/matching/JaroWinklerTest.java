package com.clinicalharmony.patientindex.matching;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class JaroWinklerTest {

    @Test
    void matchesWinklersPublishedExamples() {
        assertThat(JaroWinkler.similarity("martha", "marhta")).isCloseTo(0.9611, within(1e-4));
        assertThat(JaroWinkler.similarity("dwayne", "duane")).isCloseTo(0.84, within(1e-4));
        assertThat(JaroWinkler.similarity("dixon", "dicksonx")).isCloseTo(0.8133, within(1e-4));
    }

    @Test
    void identicalEmptyAndDisjointStrings() {
        assertThat(JaroWinkler.similarity("garcia", "garcia")).isEqualTo(1.0);
        assertThat(JaroWinkler.similarity("", "garcia")).isZero();
        assertThat(JaroWinkler.similarity(null, "garcia")).isZero();
        assertThat(JaroWinkler.similarity("abc", "xyz")).isZero();
    }

    @Test
    void isSymmetric() {
        assertThat(JaroWinkler.similarity("wilson", "willson")).isEqualTo(JaroWinkler.similarity("willson", "wilson"));
    }

    @Test
    void nearMissNamesScoreHigh() {
        assertThat(JaroWinkler.similarity("maria", "mario")).isCloseTo(0.92, within(1e-4));
        assertThat(JaroWinkler.similarity("wilson", "willson")).isGreaterThan(0.92);
    }
}
