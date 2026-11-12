package com.clinicalharmony.fhircompliance.integration;

import com.clinicalharmony.fhircompliance.gold.Hashes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The project's first real integration test: the whole fhir-compliance-service Spring context
 * against a throwaway Postgres 16 initialized from the actual docs/db-init scripts (01–19, the
 * same ones docker-compose runs), over real HTTP.
 *
 * <p>Silver → Gold: a Bronze HL7 message and the Silver patient parsed from it go in; out comes a
 * US Core compliant FHIR Patient in gold.fhir_resources, linked to a PROVISIONAL master patient,
 * with a gold.data_lineage row tracing it BRONZE → SILVER → GOLD (the BRONZE input_hash being the
 * Bronze row's payload_checksum).
 *
 * <p>CMS-0057: migration 19's seed is validated by GoldWriter on startup and served by the
 * /fhir/r4 read/search endpoints.
 *
 * <p>Needs Docker; skipped (not failed) without it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class GoldWriterIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("clinicalharmony")
            .withUsername("clinicalharmony")
            .withPassword("clinicalharmony")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("..", "docs", "db-init").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/");

    private static final String ADT = String.join("\r",
            "MSH|^~\\&|EPIC|IT_HOSPITAL|CLINICALHARMONY|CH|20261111090000||ADT^A01|IT-MSG-0001|P|2.5",
            "PID|1||IT-0001^^^IT_HOSPITAL^MR||Nakamura^Kenji||19720315|M|||88 Harbor St^^Seattle^WA^98101||206-555-0177",
            "PV1|1|O");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void silverPatientWithBronzeSourceBecomesCompliantGoldPatientWithLineage() throws Exception {
        String checksum = Hashes.sha256Hex(ADT);
        long bronzeId = jdbc.queryForObject("""
                INSERT INTO bronze.raw_messages (source_system, message_type, message_format, raw_payload,
                                                 payload_checksum, processing_status)
                VALUES ('IT_EPIC_ADT', 'HL7V2', 'TEXT', ?, ?, 'PROCESSED') RETURNING id
                """, Long.class, ADT, checksum);
        long silverId = jdbc.queryForObject("""
                INSERT INTO silver.patients (source_system, source_patient_id, mrn, first_name, last_name,
                                             date_of_birth, gender, address_line1, city, state, postal_code,
                                             phone, raw_message_id)
                VALUES ('IT_EPIC_ADT', 'IT-0001', 'IT-0001', 'Kenji', 'Nakamura', '1972-03-15', 'male',
                        '88 Harbor St', 'Seattle', 'WA', '98101', '206-555-0177', ?) RETURNING id
                """, Long.class, bronzeId);

        ResponseEntity<String> response = http.postForEntity("/api/fhir/build/patients/" + silverId, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("resourceCount").asInt()).isEqualTo(1);
        assertThat(body.get("compliantCount").asInt()).isEqualTo(1);
        long masterId = body.get("masterPatientId").asLong();
        long goldId = body.get("resources").get(0).get("goldId").asLong();

        // The FHIR Patient in Gold: US Core compliant, linked to its master, with validator findings.
        Map<String, Object> gold = jdbc.queryForMap("""
                SELECT resource_type, fhir_id, is_us_core_compliant, master_patient_id,
                       compliance_notes->>'profile' AS profile, compliance_notes->>'errorCount' AS errors,
                       resource_json->'name'->0->>'family' AS family
                FROM gold.fhir_resources WHERE id = ?
                """, goldId);
        assertThat(gold).containsEntry("resource_type", "Patient")
                .containsEntry("fhir_id", "silver-patient-" + silverId)
                .containsEntry("is_us_core_compliant", true)
                .containsEntry("master_patient_id", masterId)
                .containsEntry("profile", "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")
                .containsEntry("errors", "0")
                .containsEntry("family", "Nakamura");

        // A provisional 1:1 master, pending Phase 6's PMI.
        Map<String, Object> master = jdbc.queryForMap("""
                SELECT m.first_name, m.last_name, m.gender, m.match_status, m.match_confidence_score,
                       m.source_patient_count, x.silver_patient_id
                FROM gold.master_patients m JOIN gold.patient_source_xref x ON x.master_patient_id = m.id
                WHERE m.id = ?
                """, masterId);
        assertThat(master).containsEntry("first_name", "Kenji").containsEntry("last_name", "Nakamura")
                .containsEntry("gender", "male").containsEntry("match_status", "PROVISIONAL")
                .containsEntry("source_patient_count", 1).containsEntry("silver_patient_id", silverId);
        assertThat(master.get("match_confidence_score")).isNull();

        // Lineage: Gold Patient ← silver.patients ← bronze.raw_messages, with the Bronze checksum.
        Map<String, Object> lineage = jdbc.queryForMap("""
                SELECT source_schema, source_table, source_id, transformation_step, transformation_details::text AS details
                FROM gold.data_lineage
                WHERE target_schema = 'gold' AND target_table = 'fhir_resources' AND target_id = ?
                """, goldId);
        assertThat(lineage).containsEntry("source_schema", "silver").containsEntry("source_table", "patients")
                .containsEntry("source_id", silverId).containsEntry("transformation_step", "FHIR_MAP");
        JsonNode steps = objectMapper.readTree((String) lineage.get("details")).get("steps");
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0).get("stage").asText()).isEqualTo("BRONZE");
        assertThat(steps.get(0).get("input_hash").asText()).isEqualTo(checksum);
        assertThat(steps.get(0).get("ref").asText()).isEqualTo("bronze.raw_messages/" + bronzeId);
        assertThat(steps.get(0).get("transformer").asText()).isEqualTo("ingestion-service (HL7V2)");
        assertThat(steps.get(1).get("stage").asText()).isEqualTo("SILVER");
        assertThat(steps.get(1).get("ref").asText()).isEqualTo("silver.patients/" + silverId);
        assertThat(steps.get(2).get("stage").asText()).isEqualTo("GOLD");
        assertThat(steps.get(2).get("ref").asText()).isEqualTo("gold.fhir_resources/" + goldId);
        assertThat(steps.get(2).get("input_hash").asText()).hasSize(64);
        assertThat(steps.get(2).get("output_hash").asText()).hasSize(64);
        for (JsonNode step : steps) {
            assertThat(step.get("timestamp").asText()).isNotBlank();
            assertThat(step.get("transformer").asText()).isNotBlank();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM gold.data_lineage
                WHERE target_table = 'master_patients' AND target_id = ? AND transformation_step = 'PMI_PROVISIONAL'
                """, Integer.class, masterId)).isEqualTo(1);

        // Rebuilding is idempotent: same master, same Gold row, lineage upserted not duplicated.
        int lineageRows = jdbc.queryForObject("SELECT count(*) FROM gold.data_lineage", Integer.class);
        JsonNode rebuilt = objectMapper.readTree(
                http.postForEntity("/api/fhir/build/patients/" + silverId, null, String.class).getBody());
        assertThat(rebuilt.get("masterPatientId").asLong()).isEqualTo(masterId);
        assertThat(rebuilt.get("resources").get(0).get("goldId").asLong()).isEqualTo(goldId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gold.data_lineage", Integer.class)).isEqualTo(lineageRows);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gold.patient_source_xref WHERE silver_patient_id = ?",
                Integer.class, silverId)).isEqualTo(1);
    }

    @Test
    void seededCms0057ResourcesAreValidatedOnStartupAndServedFromGold() throws Exception {
        // Startup validation replaced every PENDING_VALIDATION marker with real findings.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM gold.fhir_resources WHERE compliance_notes->>'status' = 'PENDING_VALIDATION'",
                Integer.class)).isZero();
        List<String> nonCompliant = jdbc.queryForList(
                "SELECT fhir_id FROM gold.fhir_resources WHERE fhir_id LIKE 'cms-%' AND NOT is_us_core_compliant",
                String.class);
        assertThat(nonCompliant).containsExactly("cms-practitionerrole-2");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM gold.data_lineage WHERE transformation_step = 'SEED_LOAD' AND source_id IS NULL",
                Integer.class)).isEqualTo(13);

        // Each of the five CMS-0057 types: search by its main parameter, then read by id.
        long seed1 = jdbc.queryForObject("SELECT id FROM silver.patients WHERE source_patient_id = 'SEED-0001'", Long.class);
        long seed2 = jdbc.queryForObject("SELECT id FROM silver.patients WHERE source_patient_id = 'SEED-0002'", Long.class);
        assertSearchTotal("/fhir/r4/Coverage?patient=silver-patient-" + seed1, 1);
        assertSearchTotal("/fhir/r4/PractitionerRole?organization=cms-org-provider", 2);
        assertSearchTotal("/fhir/r4/MedicationKnowledge?code=http://www.nlm.nih.gov/research/umls/rxnorm|861007", 1);
        assertSearchTotal("/fhir/r4/MeasureReport?patient=silver-patient-" + seed1, 1);
        assertSearchTotal("/fhir/r4/Device?patient=silver-patient-" + seed2, 1);

        ResponseEntity<String> read = http.getForEntity("/fhir/r4/MeasureReport/cms-measurereport-seed-0001", String.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getHeaders().getFirst("X-Profile-Compliant")).isEqualTo("true");
        assertThat(read.getHeaders().getFirst("X-Validated-Profile"))
                .isEqualTo("http://hl7.org/fhir/us/davinci-deqm/StructureDefinition/indv-measurereport-deqm");
        ResponseEntity<String> bad = http.getForEntity("/fhir/r4/PractitionerRole/cms-practitionerrole-2", String.class);
        assertThat(bad.getHeaders().getFirst("X-Profile-Compliant")).isEqualTo("false");

        ResponseEntity<String> missing = http.getForEntity("/fhir/r4/Coverage/does-not-exist", String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(objectMapper.readTree(missing.getBody()).get("resourceType").asText()).isEqualTo("OperationOutcome");
        ResponseEntity<String> badParam = http.getForEntity("/fhir/r4/Device?colour=red", String.class);
        assertThat(badParam.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Building SEED-0001 creates its master and back-links the seeded Coverage/MeasureReport to it.
        JsonNode built = objectMapper.readTree(
                http.postForEntity("/api/fhir/build/patients/" + seed1, null, String.class).getBody());
        long master = built.get("masterPatientId").asLong();
        assertThat(jdbc.queryForList("""
                SELECT fhir_id FROM gold.fhir_resources
                WHERE fhir_id IN ('cms-coverage-seed-0001', 'cms-measurereport-seed-0001') AND master_patient_id = ?
                """, String.class, master)).hasSize(2);
    }

    private void assertSearchTotal(String url, int expected) throws Exception {
        ResponseEntity<String> response = http.getForEntity(url, String.class);
        assertThat(response.getStatusCode()).as(url).isEqualTo(HttpStatus.OK);
        JsonNode bundle = objectMapper.readTree(response.getBody());
        assertThat(bundle.get("type").asText()).isEqualTo("searchset");
        assertThat(bundle.get("total").asInt()).as(url).isEqualTo(expected);
    }
}
