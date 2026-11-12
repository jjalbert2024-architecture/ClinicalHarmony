package com.clinicalharmony.patientindex.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The seed scenarios of docs/db-init/21, scored with the default PMI_MATCH weights. */
class PatientMatcherTest {

    private final PatientMatcher matcher = new PatientMatcher();
    private final MatchSettings settings = MatchSettings.DEFAULTS;

    // docs/db-init/17 + 21
    static final PatientRecord SEED_0001 = rec(1, "SEED_EPIC_ADT", "MRN100001", "Maria", "Garcia", "1968-04-12", "female",
            "1200 Congress Ave", "78701", "512-555-0101");
    static final PatientRecord SEED_0002 = rec(2, "SEED_CERNER_FHIR", "MRN200002", "James", "Wilson", "1955-09-30", "male",
            "45 Beacon St", "02108", "617-555-0202");
    static final PatientRecord PMI_0001 = rec(4, "SEED_CLAIMS_CSV", null, "Maria", "Garcia", "1968-04-12", "female",
            "1200 Congress Avenue", "78701", "(512) 555-0101");
    static final PatientRecord PMI_0002 = rec(5, "SEED_LAB_LIS", "LAB-88231", "Jim", "Wilson", "1955-09-30", "male",
            "45 Beacon Street", "02108", null);
    static final PatientRecord PMI_0003 = rec(6, "SEED_EPIC_ADT", "MRN100318", "Maria", "Garcia", "1968-12-04", "female",
            "77 Rainey St", "78701", null);
    static final PatientRecord PMI_0004 = rec(7, "SEED_EPIC_ADT", "MRN100077", "Mario", "Garcia", "1968-04-12", "male",
            "1200 Congress Ave", "78701", "512-555-0101");
    static final PatientRecord PMI_0005 = rec(8, "SEED_CLAIMS_CSV", null, "Robert", "Wilson", "1955-09-30", "male",
            "9 Camelback Rd", "85001", "602-555-0303");
    static final PatientRecord PMI_0006 = rec(9, "SEED_CERNER_FHIR", "MRN200002", "James", "Willson", "1955-09-30", "male",
            "45 Beacon St", "02108", "617-555-0202");

    static PatientRecord rec(long id, String source, String mrn, String first, String last, String dob, String gender,
                             String address, String zip, String phone) {
        return new PatientRecord(id, id + 100, "PROVISIONAL", source, "S-" + id, mrn, first, last, LocalDate.parse(dob),
                gender, address, null, null, zip, phone);
    }

    private static FieldScore field(MatchScore s, String name) {
        return s.fields().stream().filter(f -> f.field().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void exactNameDobAndPhoneIsADeterministicMatchAcrossFormats() {
        MatchScore s = matcher.score(SEED_0001, PMI_0001, settings);

        assertThat(s.method()).isEqualTo(MatchScore.DETERMINISTIC);
        assertThat(s.deterministicRule()).isEqualTo(MatchScore.EXACT_NAME_DOB_PLUS_PHONE_OR_ZIP);
        assertThat(s.score()).isEqualByComparingTo("1.0");
        assertThat(s.weightedScore()).isEqualByComparingTo("1.0");
        assertThat(s.decision()).isEqualTo(MatchScore.AUTO_MERGE);
        assertThat(field(s, "address").outcome()).isEqualTo(FieldScore.EXACT); // Avenue == Ave
        assertThat(field(s, "phone").outcome()).isEqualTo(FieldScore.EXACT);   // (512) 555-0101 == 512-555-0101
    }

    @Test
    void nicknameAndMissingPhoneStillAutoMerge() {
        MatchScore s = matcher.score(SEED_0002, PMI_0002, settings);

        // 4 + 2 (Jim≈James) + 5 + 0.5 + 1.5 + 2.5 = 15.5 of a possible 17 (phone missing on one side)
        assertThat(field(s, "firstName").outcome()).isEqualTo(FieldScore.NICKNAME);
        assertThat(field(s, "phone").outcome()).isEqualTo(FieldScore.MISSING);
        assertThat(s.totalWeight()).isEqualTo(15.5);
        assertThat(s.maxWeight()).isEqualTo(17.0);
        assertThat(s.score()).isEqualByComparingTo("0.9118");
        assertThat(s.method()).isEqualTo(MatchScore.PROBABILISTIC);
        assertThat(s.decision()).isEqualTo(MatchScore.AUTO_MERGE);
    }

    @Test
    void sameSourceAndMrnIsDeterministicDespiteATypo() {
        MatchScore s = matcher.score(SEED_0002, PMI_0006, settings);

        assertThat(s.deterministicRule()).isEqualTo(MatchScore.SAME_SOURCE_MRN);
        assertThat(field(s, "lastName").outcome()).isEqualTo(FieldScore.SIMILAR);
        assertThat(s.score()).isEqualByComparingTo("1.0");
        assertThat(s.weightedScore()).isLessThan(s.score());
    }

    @Test
    void swappedDayAndMonthGoesToReview() {
        MatchScore s = matcher.score(SEED_0001, PMI_0003, settings);

        // 4 + 3.5 + 2 (d/m swap) + 0.5 + 1.5 - 0.5 = 11 of 17
        assertThat(field(s, "birthDate").outcome()).isEqualTo(FieldScore.PARTIAL);
        assertThat(s.score()).isEqualByComparingTo("0.6471");
        assertThat(s.decision()).isEqualTo(MatchScore.REVIEW);
    }

    @Test
    void oppositeGenderTwinIsHeldForReviewNotAutoMerged() {
        MatchScore s = matcher.score(SEED_0001, PMI_0004, settings);

        // 4 + 2 (JW maria/mario 0.92) + 5 - 4 (gender) + 4 + 1.5 + 2.5 = 15 of 21
        assertThat(field(s, "gender").outcome()).isEqualTo(FieldScore.DISAGREE);
        assertThat(field(s, "firstName").similarity()).isEqualTo(0.92);
        assertThat(s.score()).isEqualByComparingTo("0.7143");
        assertThat(s.decision()).isEqualTo(MatchScore.REVIEW);
    }

    @Test
    void sameSurnameAndDobButNothingElseIsNoMatch() {
        MatchScore s = matcher.score(SEED_0002, PMI_0005, settings);

        // 4 - 3 + 5 + 0.5 - 1 - 0.5 - 0.5 = 4.5 of 21
        assertThat(s.score()).isEqualByComparingTo("0.2143");
        assertThat(s.decision()).isEqualTo(MatchScore.NO_MATCH);
    }

    @Test
    void contradictionsBlockDeterministicRules() {
        PatientRecord sameMrnOtherSex = rec(20, "SEED_CERNER_FHIR", "MRN200002", "James", "Wilson", "1955-09-30", "female",
                "45 Beacon St", "02108", "617-555-0202");
        MatchScore s = matcher.score(SEED_0002, sameMrnOtherSex, settings);

        assertThat(s.method()).isEqualTo(MatchScore.PROBABILISTIC);
        assertThat(s.deterministicRule()).isNull();
    }

    @Test
    void sameMrnInDifferentSourcesIsNotDeterministic() {
        PatientRecord otherSource = rec(21, "SEED_LAB_LIS", "MRN100001", "Ana", "Lopez", "1990-01-01", "female", null, null, null);
        assertThat(matcher.score(SEED_0001, otherSource, settings).deterministicRule()).isNull();
    }

    @Test
    void blankNamesAreMissingNotDisagreements() {
        PatientRecord blank = rec(3, "SEED_CLAIMS_CSV", null, "", "", "1990-01-01", "unknown", null, null, null);
        PatientRecord other = rec(22, "X", null, "Ana", "Lopez", "1990-01-01", "female", null, null, null);
        MatchScore s = matcher.score(blank, other, settings);

        assertThat(field(s, "firstName").outcome()).isEqualTo(FieldScore.MISSING);
        assertThat(field(s, "gender").outcome()).isEqualTo(FieldScore.MISSING);
        assertThat(s.maxWeight()).isEqualTo(5.0); // only DOB present on both
        assertThat(s.score()).isEqualByComparingTo("1.0");
        // a perfect score on DOB alone is still PROBABILISTIC — no deterministic rule fires without names
        assertThat(s.method()).isEqualTo(MatchScore.PROBABILISTIC);
        // ... and 5 points of evidence is below minComparableWeight (10), so a human decides
        assertThat(s.decision()).isEqualTo(MatchScore.REVIEW);
    }

    @Test
    void initialAndOneComponentDobDifference() {
        PatientRecord a = rec(30, "A", null, "J", "Wilson", "1955-09-30", "male", null, null, null);
        PatientRecord b = rec(31, "B", null, "James", "Wilson", "1956-09-30", "male", null, null, null);
        MatchScore s = matcher.score(a, b, settings);

        assertThat(field(s, "firstName").outcome()).isEqualTo(FieldScore.INITIAL);
        assertThat(field(s, "birthDate").outcome()).isEqualTo(FieldScore.PARTIAL);
    }

    @Test
    void orderOfArgumentsDoesNotMatter() {
        assertThat(matcher.score(PMI_0004, SEED_0001, settings)).isEqualTo(matcher.score(SEED_0001, PMI_0004, settings));
    }

    @Test
    void settingsComeFromRuleExpressionWithDefaultsForGaps() throws Exception {
        MatchSettings s = MatchSettings.fromJson(new ObjectMapper().readTree(
                "{\"thresholds\": {\"autoMerge\": 0.95}, \"weights\": {\"gender\": {\"disagree\": -10}}}"));

        assertThat(s.autoMergeThreshold()).isEqualTo(0.95);
        assertThat(s.reviewThreshold()).isEqualTo(0.60);
        assertThat(s.minComparableWeight()).isEqualTo(10.0);
        assertThat(s.gender().disagree()).isEqualTo(-10);
        assertThat(s.gender().exact()).isEqualTo(0.5);
        assertThat(MatchSettings.fromJson(null)).isEqualTo(MatchSettings.DEFAULTS);
        // a stricter auto-merge bar pushes the Jim Wilson pair into review
        assertThat(matcher.score(SEED_0002, PMI_0002, s).decision()).isEqualTo(MatchScore.REVIEW);
    }

    @Test
    void invalidThresholdsAreRejected() {
        assertThatThrownBy(() -> MatchSettings.fromJson(new ObjectMapper().readTree(
                "{\"thresholds\": {\"autoMerge\": 0.5, \"review\": 0.7}}")))
                .isInstanceOf(IllegalStateException.class);
    }
}
