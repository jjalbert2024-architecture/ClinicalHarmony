package com.clinicalharmony.fhircompliance.service.ccda;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class Hl7DatesTest {

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "2026, 2026",
            "202609, 2026-09",
            "20260910, 2026-09-10",
            "20260910083000-0500, 2026-09-10T08:30:00-05:00",
            "202609100830+0130, 2026-09-10T08:30:00+01:30",
            "20260910083015.123+0000, 2026-09-10T08:30:15+00:00",
            // time without a timezone: truncated to the date rather than inventing an offset
            "20260910083000, 2026-09-10",
            "not-a-date, NULL",
            "NULL, NULL"
    })
    void convertsTsToFhirDateTime(String ts, String expected) {
        assertThat(Hl7Dates.toFhirDateTime(ts)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "19780412, 1978-04-12",
            "19780412103000-0500, 1978-04-12",
            "1978, 1978",
            "'', NULL"
    })
    void convertsTsToFhirDate(String ts, String expected) {
        assertThat(Hl7Dates.toFhirDate(ts)).isEqualTo(expected);
    }
}
