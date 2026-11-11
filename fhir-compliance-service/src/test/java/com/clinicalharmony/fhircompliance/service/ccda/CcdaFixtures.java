package com.clinicalharmony.fhircompliance.service.ccda;

import com.clinicalharmony.fhircompliance.validation.UsCoreValidatorFixture;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** Test C-CDA documents and a fully-wired conversion service using the REAL US Core validator. */
final class CcdaFixtures {

    static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);

    private CcdaFixtures() {
    }

    static byte[] load(String name) {
        try (InputStream in = CcdaFixtures.class.getClassLoader().getResourceAsStream("ccda/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No test resource ccda/" + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String loadString(String name) {
        return new String(load(name), StandardCharsets.UTF_8);
    }

    static CcdaConversionService service(long maxBytes) {
        return new CcdaConversionService(new CcdaPatientMapper(),
                List.of(new CcdaProblemMapper(), new CcdaMedicationMapper(), new CcdaResultMapper()),
                UsCoreValidatorFixture.validator(), UsCoreValidatorFixture.fhirContext(), new ObjectMapper(),
                FIXED_CLOCK, maxBytes);
    }

    static CcdaConversionService service() {
        return service(5_242_880);
    }

    /** A minimal ClinicalDocument wrapper: {@code patientRole} XML + {@code body} section components. */
    static byte[] document(String patientRole, String body) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ClinicalDocument xmlns="urn:hl7-org:v3" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                  xmlns:sdtc="urn:hl7-org:sdtc">
                  <id root="2.16.840.1.113883.19.5.99999.1" extension="UNIT-TEST"/>
                  <title>unit test</title>
                  <recordTarget>%s</recordTarget>
                  <component><structuredBody>%s</structuredBody></component>
                </ClinicalDocument>
                """.formatted(patientRole, body);
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    static final String MINIMAL_PATIENT_ROLE = """
            <patientRole>
              <id root="2.16.840.1.113883.19.5.99999.2" extension="UT-1"/>
              <patient>
                <name><given>Test</given><family>Patient</family></name>
                <administrativeGenderCode code="M" codeSystem="2.16.840.1.113883.5.1"/>
                <birthTime value="19700101"/>
              </patient>
            </patientRole>
            """;
}
