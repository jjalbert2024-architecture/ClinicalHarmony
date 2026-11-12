package com.clinicalharmony.patientindex.matching;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizerTest {

    @Test
    void namesLoseCaseAccentsAndPunctuation() {
        assertThat(Normalizer.name(" José ")).isEqualTo("jose");
        assertThat(Normalizer.name("Garcia-Lopez")).isEqualTo("garcialopez");
        assertThat(Normalizer.name("O'Brien")).isEqualTo("obrien");
        assertThat(Normalizer.name("")).isNull();
        assertThat(Normalizer.name("--")).isNull();
    }

    @Test
    void phonesReduceToTenDigits() {
        assertThat(Normalizer.phone("(512) 555-0101")).isEqualTo("5125550101");
        assertThat(Normalizer.phone("+1 512.555.0101")).isEqualTo("5125550101");
        assertThat(Normalizer.phone("555-0101")).isNull();
        assertThat(Normalizer.phone(null)).isNull();
    }

    @Test
    void zipPlusFourBecomesFiveDigits() {
        assertThat(Normalizer.postalCode("78701-1234")).isEqualTo("78701");
        assertThat(Normalizer.postalCode("787")).isNull();
    }

    @Test
    void addressesUseUspsAbbreviations() {
        assertThat(Normalizer.address("1200 Congress Avenue,")).isEqualTo("1200 congress ave");
        assertThat(Normalizer.address("45 Beacon Street")).isEqualTo(Normalizer.address("45 Beacon St"));
        assertThat(Normalizer.address("9 North Main St #4")).isEqualTo("9 n main st apt 4");
        assertThat(Normalizer.address("  ")).isNull();
    }

    @Test
    void unknownGenderAndBlankMrnAreMissing() {
        assertThat(Normalizer.gender("unknown")).isNull();
        assertThat(Normalizer.gender("Female")).isEqualTo("female");
        assertThat(Normalizer.mrn(" mrn200002 ")).isEqualTo("MRN200002");
        assertThat(Normalizer.mrn(" ")).isNull();
    }

    @Test
    void nicknamesShareGroups() {
        assertThat(Nicknames.equivalent("jim", "james")).isTrue();
        assertThat(Nicknames.equivalent("bob", "robert")).isTrue();
        assertThat(Nicknames.equivalent("alex", "sasha")).isTrue();
        assertThat(Nicknames.equivalent("maria", "mario")).isFalse();
        assertThat(Nicknames.equivalent("james", null)).isFalse();
    }
}
