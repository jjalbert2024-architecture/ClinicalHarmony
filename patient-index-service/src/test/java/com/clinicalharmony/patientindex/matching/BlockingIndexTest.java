package com.clinicalharmony.patientindex.matching;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.clinicalharmony.patientindex.matching.PatientMatcherTest.*;
import static org.assertj.core.api.Assertions.assertThat;

class BlockingIndexTest {

    private static Set<String> pairs(List<PatientRecord> records) {
        return BlockingIndex.candidatePairs(records).stream()
                .map(p -> p.a().silverPatientId() + "-" + p.b().silverPatientId())
                .collect(Collectors.toSet());
    }

    @Test
    void sharedDobSpellingVariantOrPhoneBlockTogether() {
        PatientRecord ana = rec(40, "X", null, "Ana", "Lopez", "1990-01-01", "female", null, null, "303-555-0000");
        Set<String> pairs = pairs(List.of(SEED_0001, SEED_0002, PMI_0003, PMI_0005, PMI_0006, ana));

        assertThat(pairs).contains("1-6");   // Garcia/Garcia: metaphone + 1968, despite DOB swap
        assertThat(pairs).contains("2-9");   // Wilson/Willson: same DOB, same phone
        assertThat(pairs).contains("2-8");   // same DOB
        assertThat(pairs).doesNotContain("1-40", "1-2");
        assertThat(pairs).allMatch(p -> Long.parseLong(p.split("-")[0]) < Long.parseLong(p.split("-")[1]));
    }

    @Test
    void aPairSharingSeveralKeysIsEmittedOnce() {
        assertThat(BlockingIndex.candidatePairs(List.of(SEED_0002, PMI_0006))).hasSize(1);
    }

    @Test
    void recordsWithoutLastNameStillBlockOnDob() {
        PatientRecord blank = rec(3, "SEED_CLAIMS_CSV", null, "", "", "1955-09-30", "unknown", null, null, null);
        assertThat(BlockingIndex.keys(blank)).containsExactly("DOB:1955-09-30");
        assertThat(pairs(List.of(blank, SEED_0002))).containsExactly("2-3");
    }
}
