package com.clinicalharmony.gateway.integration;

import com.clinicalharmony.gateway.support.StubHttpServer;
import com.clinicalharmony.gateway.support.TestJwts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * api-gateway end to end: a real Postgres initialized from docs/db-init (so the gateway logs in as
 * migration 22's read-only role, exactly as in compose), real HTTP in, and a JDK stub server
 * standing in for Keycloak's token endpoint and the downstream services — with ontology-service
 * pointed at a dead port to exercise "service down".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@Import(TestJwts.class)
class ApiGatewayIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("clinicalharmony")
            .withUsername("clinicalharmony")
            .withPassword("clinicalharmony")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("..", "docs", "db-init").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/");

    static final StubHttpServer SERVICES = new StubHttpServer();
    static final String DEAD = StubHttpServer.deadBaseUrl();

    static {
        if (org.testcontainers.DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // the gateway's own least-privilege login, not the owner role
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "clinicalharmony_gateway_ro");
        registry.add("spring.datasource.password", () -> "gateway-ro-dev-password");
        registry.add("gateway.service-token.token-uri", () -> SERVICES.baseUrl() + "/token");
        registry.add("gateway.services.ingestion.base-url", SERVICES::baseUrl);
        registry.add("gateway.services.ontology.base-url", () -> DEAD);
        registry.add("gateway.services.rules-engine.base-url", SERVICES::baseUrl);
        registry.add("gateway.services.fhir-compliance.base-url", SERVICES::baseUrl);
        registry.add("gateway.services.patient-index.base-url", SERVICES::baseUrl);
        registry.add("gateway.services.patient-index.read-timeout", () -> "1s");
    }

    static long maria;          // master for SEED-0001 + PMI-0001
    static long mariaE119;      // silver.conditions id of SEED-0001's E11.9
    static long goldCondition;  // gold.fhir_resources id mapped from it
    static long bronzeId;

    /** Gold state GoldWriter + the PMI would have produced, written with the OWNER role. */
    @BeforeAll
    static void seed() {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "clinicalharmony",
                "clinicalharmony"));
        if (admin.queryForObject("SELECT count(*) FROM gold.master_patients", Integer.class) > 0) {
            return;
        }
        long seed1 = admin.queryForObject("SELECT id FROM silver.patients WHERE source_patient_id = 'SEED-0001'", Long.class);
        long pmi1 = admin.queryForObject("SELECT id FROM silver.patients WHERE source_patient_id = 'PMI-0001'", Long.class);
        for (long silverId : admin.queryForList("SELECT id FROM silver.patients ORDER BY id", Long.class)) {
            if (silverId == pmi1) {
                continue; // linked to Maria's master below
            }
            long master = admin.queryForObject("""
                    INSERT INTO gold.master_patients (first_name, last_name, date_of_birth, gender, match_status)
                    SELECT first_name, last_name, date_of_birth, gender, 'MATCHED' FROM silver.patients WHERE id = ?
                    RETURNING id""", Long.class, silverId);
            admin.update("INSERT INTO gold.patient_source_xref (master_patient_id, silver_patient_id) VALUES (?, ?)",
                    master, silverId);
            if (silverId == seed1) {
                maria = master;
            }
        }
        admin.update("INSERT INTO gold.patient_source_xref (master_patient_id, silver_patient_id) VALUES (?, ?)", maria, pmi1);
        admin.update("UPDATE gold.master_patients SET source_patient_count = 2 WHERE id = ?", maria);

        bronzeId = admin.queryForObject("""
                INSERT INTO bronze.raw_messages (source_system, message_type, message_format, raw_payload, payload_checksum)
                VALUES ('SEED_EPIC_ADT', 'HL7V2', 'TEXT', 'MSH|^~\\&|EPIC', 'abc123') RETURNING id""", Long.class);
        admin.update("""
                INSERT INTO bronze.raw_messages (source_system, message_type, message_format, raw_payload, processing_status,
                                                 error_message)
                VALUES ('LAB_X', 'CLAIMS_CSV', 'CSV', 'a,b\\n1', 'QUARANTINED', 'Row 2 has 1 columns, header has 2')""");
        // HL7 message types derived from MSH-9 (all PROCESSED so the QUARANTINED counts above stay 1)
        for (String payload : List.of(
                "MSH|^~\\&|LAB|REGIONAL_LAB|CH|ING|20261102||ORU^R01^ORU_R01|T1|P|2.5\rPID|1||X1",
                "MSH|^~\\&|EHR|NC|CH|ING|20261102||ADT^A08^ADT_A01|T2|P|2.5\nPID|1||X2",   // \n terminators
                "MSH|^~\\&|EHR|NC|CH|ING|20261102||ADT|T3|P|2.5\rPID|1||X3",              // MSH-9.1 only
                "MSH|^~\\&|EHR\rPID|1||X4|a|b|c|d|e|f|g|h",                            // truncated header
                "PID|1||X5")) {                                                       // no MSH at all
            admin.update("""
                    INSERT INTO bronze.raw_messages (source_system, message_type, message_format, raw_payload, processing_status)
                    VALUES ('HL7TYPE_TEST', 'HL7V2', 'TEXT', ?, 'PROCESSED')""", payload);
        }
        admin.update("""
                INSERT INTO bronze.raw_messages (source_system, message_type, message_format, raw_payload, processing_status)
                VALUES ('HL7TYPE_TEST', 'FHIR_JSON', 'JSON', '{"resourceType":"Patient","x":"MSH|a|b|c|d|e|f|g|ORU^R01"}', 'PROCESSED')""");
        mariaE119 = admin.queryForObject("SELECT id FROM silver.conditions WHERE patient_id = ? AND code = 'E11.9'",
                Long.class, seed1);
        admin.update("UPDATE silver.conditions SET raw_message_id = ? WHERE id = ?", bronzeId, mariaE119);
        goldCondition = admin.queryForObject("""
                INSERT INTO gold.fhir_resources (resource_type, fhir_id, master_patient_id, resource_json, is_us_core_compliant,
                                                 compliance_notes)
                VALUES ('Condition', 'silver-condition-' || ?, ?, '{"resourceType":"Condition","id":"x"}', true,
                        '{"compliant":true,"errorCount":0,"warningCount":1,"profile":"us-core-condition"}')
                RETURNING id""", Long.class, mariaE119, maria);
        admin.update("""
                INSERT INTO gold.data_lineage (source_schema, source_table, source_id, target_schema, target_table, target_id,
                                               transformation_step, transformation_details)
                VALUES ('silver', 'conditions', ?, 'gold', 'fhir_resources', ?, 'FHIR_MAP',
                        '{"steps":[{"stage":"BRONZE"},{"stage":"SILVER"},{"stage":"GOLD"}]}')""", mariaE119, goldCondition);
        admin.update("""
                INSERT INTO silver.validation_reports (entity_type, entity_id, patient_id, rule_code, severity, message, bad_value)
                VALUES ('CONDITION', 5, 3, 'CODE-001', 'ERROR', 'Invalid ICD-10 code', '')""");
        admin.update("""
                INSERT INTO silver.validation_reports (entity_type, entity_id, patient_id, rule_code, severity, message, is_resolved)
                VALUES ('OBSERVATION', 1, 1, 'LAB_REFERENCE_RANGE', 'WARNING', 'High HbA1c', true)""");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate gatewayJdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void stubs() {
        SERVICES.reset();
        SERVICES.route("/token", r -> new StubHttpServer.Response(200, "{\"access_token\":\"gw-svc\",\"expires_in\":300}"));
        SERVICES.route("/actuator/health", r -> new StubHttpServer.Response(200, "{\"status\":\"UP\"}"));
    }

    private TestRestTemplate as(String token) {
        RestTemplateBuilder builder = new RestTemplateBuilder().rootUri("http://localhost:" + port);
        if (token != null) {
            builder = builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return new TestRestTemplate(builder);
    }

    private JsonNode getJson(String path) throws Exception {
        ResponseEntity<String> response = as(TestJwts.viewer()).getForEntity(path, String.class);
        assertThat(response.getStatusCode()).as(path + " → " + response.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    // ---- read models over real SQL ---------------------------------------------------------------

    @Test
    void patientSearchFindsAMasterByAnyLinkedRecordsMrnOrSourceId() throws Exception {
        // "Name or MRN": SEED-0001's MRN, any case → Maria's master, and only her
        JsonNode byMrn = getJson("/api/v1/patients?q=mrn100001");
        assertThat(byMrn.at("/total").asInt()).isEqualTo(1);
        assertThat(byMrn.at("/items/0/id").asLong()).isEqualTo(maria);
        // PMI-0001 (claims, no MRN) is her SECOND linked record: its source id finds the same master
        assertThat(getJson("/api/v1/patients?q=PMI-0001").at("/items/0/id").asLong()).isEqualTo(maria);
        // exact, not partial: a fragment of an MRN matches nothing
        assertThat(getJson("/api/v1/patients?q=MRN1000").at("/total").asInt()).isZero();
    }

    @Test
    void patientTimelineJoinsEverySourceTerminologyAndGold() throws Exception {
        JsonNode search = getJson("/api/v1/patients?q=garc"); // migration 21 seeds more Garcias
        assertThat(search.at("/total").asInt()).isEqualTo(search.at("/items").size()).isGreaterThanOrEqualTo(1);
        JsonNode found = null;
        for (JsonNode m : search.at("/items")) {
            assertThat(m.path("lastName").asText().toLowerCase()).contains("garc");
            if (m.path("id").asLong() == maria) {
                found = m;
            }
        }
        assertThat(found).isNotNull();
        assertThat(found.at("/sourceSystems").toString()).contains("SEED_EPIC_ADT");

        JsonNode detail = getJson("/api/v1/patients/" + maria);
        assertThat(detail.at("/sources").size()).isEqualTo(2);
        assertThat(detail.at("/fhirResources/0/resourceType").asText()).isEqualTo("Condition");

        JsonNode timeline = getJson("/api/v1/patients/" + maria + "/timeline");
        assertThat(timeline.at("/eventCount").asInt()).isEqualTo(7); // 2 conditions, 3 labs, 2 meds from SEED-0001
        JsonNode e119 = null;
        for (JsonNode e : timeline.at("/events")) {
            if ("E11.9".equals(e.path("code").asText())) {
                e119 = e;
            }
        }
        assertThat(e119).isNotNull();
        assertThat(e119.at("/codeKnown").asBoolean()).isTrue();
        assertThat(e119.at("/fhirResourceId").asLong()).isEqualTo(goldCondition);
        assertThat(e119.at("/rawMessageId").asLong()).isEqualTo(bronzeId);
        assertThat(e119.at("/occurredAt").asText()).startsWith("2015-06-01");
        // newest first
        assertThat(timeline.at("/events/0/kind").asText()).isEqualTo("OBSERVATION");

        JsonNode medsOnly = getJson("/api/v1/patients/" + maria + "/timeline?types=MEDICATION&from=2016-01-01");
        assertThat(medsOnly.at("/eventCount").asInt()).isEqualTo(1);
        assertThat(medsOnly.at("/events/0/code").asText()).isEqualTo("83367");
    }

    @Test
    void ontologyCoverageAndValidationReport() throws Exception {
        JsonNode coverage = getJson("/api/v1/ontology/coverage");
        JsonNode icd10 = coverage.at("/terminologies/0");
        assertThat(icd10.at("/terminology").asText()).isEqualTo("ICD-10");
        assertThat(icd10.at("/topUnknownCodes/0/code").asText()).isEmpty(); // SEED-0003's blank code
        assertThat(icd10.at("/openFindings").asInt()).isEqualTo(1);
        assertThat(coverage.at("/mappings").size()).isPositive();

        JsonNode summary = getJson("/api/v1/validation/summary");
        assertThat(summary.at("/total").asInt()).isEqualTo(2);
        assertThat(summary.at("/open").asInt()).isEqualTo(1);
        assertThat(summary.at("/bySeverity/0/key").asText()).isEqualTo("ERROR");

        JsonNode findings = getJson("/api/v1/validation/findings?resolved=false");
        assertThat(findings.at("/total").asInt()).isEqualTo(1);
        assertThat(findings.at("/items/0/ruleName").asText()).isNotBlank();

        JsonNode rules = getJson("/api/v1/validation/rules");
        boolean sawToggleable = false;
        for (JsonNode rule : rules) {
            if (rule.path("code").asText().equals("CODE-001")) {
                assertThat(rule.path("toggleable").asBoolean()).isFalse();
                assertThat(rule.path("openFindings").asInt()).isEqualTo(1);
            }
            sawToggleable |= rule.path("toggleable").asBoolean();
        }
        assertThat(sawToggleable).isTrue();
    }

    @Test
    void fhirListSortsIdsNumericallyAndLineageSummaryFollowsThePipeline() throws Exception {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "clinicalharmony",
                "clinicalharmony"));
        for (String n : List.of("100", "8", "80", "9")) {
            admin.update("""
                    INSERT INTO gold.fhir_resources (resource_type, fhir_id, resource_json, is_us_core_compliant)
                    VALUES ('Observation', 'natsort-obs-' || ?, '{"resourceType":"Observation"}', true)
                    ON CONFLICT DO NOTHING""", n);
        }
        JsonNode asc = getJson("/api/v1/fhir/resources?q=natsort-obs-&sort=resource");
        assertThat(asc.get("items").findValuesAsText("fhirId"))
                .containsExactly("natsort-obs-8", "natsort-obs-9", "natsort-obs-80", "natsort-obs-100");
        JsonNode desc = getJson("/api/v1/fhir/resources?q=natsort-obs-&sort=resource&dir=desc");
        assertThat(desc.get("items").findValuesAsText("fhirId"))
                .containsExactly("natsort-obs-100", "natsort-obs-80", "natsort-obs-9", "natsort-obs-8");
        assertThat(getJson("/api/v1/fhir/resources?sort=updated&dir=desc").at("/total").asInt()).isPositive();
        assertThat(getJson("/api/v1/fhir/resources?sort=master").at("/total").asInt()).isPositive();
        assertThat(getJson("/api/v1/fhir/resources?sort=compliance&dir=desc").at("/total").asInt()).isPositive();

        long obs = admin.queryForObject("SELECT id FROM gold.fhir_resources WHERE fhir_id = 'natsort-obs-8'", Long.class);
        for (String step : List.of("SEED_LOAD", "PMI_MERGE", "API_GENERATE", "PMI_PROVISIONAL")) {
            admin.update("""
                    INSERT INTO gold.data_lineage (source_schema, source_table, target_schema, target_table, target_id,
                                                   transformation_step, transformation_details)
                    VALUES ('test', 'natsort', 'gold', 'fhir_resources', ?, ?, '{}') ON CONFLICT DO NOTHING""", obs, step);
        }
        JsonNode summary = getJson("/api/v1/lineage/summary");
        assertThat(summary.findValuesAsText("step"))
                .containsSubsequence("FHIR_MAP", "PMI_PROVISIONAL", "PMI_MERGE", "SEED_LOAD", "API_GENERATE");
    }

    @Test
    void fhirViewerFiltersByPatientOriginSourceAndCodeNewestFirst() throws Exception {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "clinicalharmony",
                "clinicalharmony"));
        if (admin.queryForObject("SELECT count(*) FROM silver.patients WHERE source_system = 'FV_CLINIC'", Integer.class) == 0) {
            long silver = admin.queryForObject("""
                    INSERT INTO silver.patients (source_system, source_patient_id, mrn, first_name, last_name, date_of_birth, gender)
                    VALUES ('FV_CLINIC', 'FV123', 'FV123', 'Fiona', 'Viewer', '1971-05-06', 'female') RETURNING id""", Long.class);
            long master = admin.queryForObject("""
                    INSERT INTO gold.master_patients (first_name, last_name, date_of_birth, gender, match_status)
                    VALUES ('Fiona', 'Viewer', '1971-05-06', 'female', 'MATCHED') RETURNING id""", Long.class);
            admin.update("INSERT INTO gold.patient_source_xref (master_patient_id, silver_patient_id) VALUES (?, ?)", master, silver);
            String ref = "Patient/silver-patient-" + silver;
            // fhir_id, type, json, master, lineage step, first lineage stage, updated_at
            Object[][] rows = {
                    {"silver-patient-" + silver, "Patient", "{\"resourceType\":\"Patient\"}", master, "FHIR_MAP", "BRONZE", "2026-11-18T10:00:00Z"},
                    {"fv-allergy-1", "AllergyIntolerance", "{\"resourceType\":\"AllergyIntolerance\",\"patient\":{\"reference\":\"" + ref
                            + "\"},\"code\":{\"coding\":[{\"system\":\"http://www.nlm.nih.gov/research/umls/rxnorm\",\"code\":\"723\",\"display\":\"Amoxicillin\"}]}}",
                            master, "FHIR_MAP", "SILVER", "2026-11-18T12:00:00Z"},
                    {"fv-med-1", "MedicationRequest", "{\"resourceType\":\"MedicationRequest\",\"subject\":{\"reference\":\"" + ref
                            + "\"},\"medicationCodeableConcept\":{\"text\":\"Metformin 500 MG Oral Tablet\",\"coding\":[{\"code\":\"6809\"}]}}",
                            master, "API_GENERATE", null, "2026-11-18T11:00:00Z"},
                    {"fv-cov-1", "Coverage", "{\"resourceType\":\"Coverage\"}", null, "SEED_LOAD", null, "2026-11-18T09:00:00Z"}};
            for (Object[] r : rows) {
                long gold = admin.queryForObject("""
                        INSERT INTO gold.fhir_resources (resource_type, fhir_id, resource_json, master_patient_id, is_us_core_compliant,
                                                         updated_at)
                        VALUES (?, ?, ?::jsonb, ?, true, ?::timestamptz) RETURNING id""", Long.class, r[1], r[0], r[2], r[3], r[6]);
                admin.update("""
                        INSERT INTO gold.data_lineage (source_schema, source_table, target_schema, target_table, target_id,
                                                       transformation_step, transformation_details)
                        VALUES ('test', 'fv', 'gold', 'fhir_resources', ?, ?, ?::jsonb)""", gold, r[4],
                        r[5] == null ? "{}" : "{\"steps\":[{\"stage\":\"" + r[5] + "\"},{\"stage\":\"GOLD\"}]}");
            }
        }

        // patient: exact MRN in any case, or part of the name — every resource of that person (Coverage has no master)
        JsonNode byMrn = getJson("/api/v1/fhir/resources?patient=fv123");
        assertThat(byMrn.get("items").findValuesAsText("fhirId")).containsExactly("fv-allergy-1", "fv-med-1",
                byMrn.at("/items/2/fhirId").asText());   // newest first by default: 12:00, 11:00, 10:00
        assertThat(byMrn.at("/items/2/resourceType").asText()).isEqualTo("Patient");
        assertThat(getJson("/api/v1/fhir/resources?patient=viewer").at("/total").asInt()).isEqualTo(3);
        assertThat(getJson("/api/v1/fhir/resources?patient=FV12").at("/total").asInt()).isZero();   // partial MRN: no

        // each row says where it came from and whose it is
        assertThat(byMrn.get("items").findValuesAsText("origin")).containsExactly("silver-seed", "api", "pipeline");
        assertThat(byMrn.get("items").findValuesAsText("sourceSystem")).containsOnly("FV_CLINIC");
        assertThat(byMrn.get("items").findValuesAsText("sourcePatientId")).containsOnly("FV123");

        JsonNode src = getJson("/api/v1/fhir/resources?sourceSystem=FV_CLINIC&origin=pipeline");
        assertThat(src.get("items").findValuesAsText("resourceType")).containsExactly("Patient");
        JsonNode seed = getJson("/api/v1/fhir/resources?origin=gold-seed&q=fv-cov");
        assertThat(seed.at("/total").asInt()).isEqualTo(1);
        assertThat(seed.at("/items/0/sourceSystem").isNull()).isTrue();

        // code: exact code or part of the display / text
        assertThat(getJson("/api/v1/fhir/resources?sourceSystem=FV_CLINIC&code=723").get("items").findValuesAsText("fhirId"))
                .containsExactly("fv-allergy-1");
        assertThat(getJson("/api/v1/fhir/resources?sourceSystem=FV_CLINIC&code=metformin").get("items").findValuesAsText("fhirId"))
                .containsExactly("fv-med-1");
        assertThat(getJson("/api/v1/fhir/resources?sourceSystem=FV_CLINIC&code=72").at("/total").asInt()).isZero();

        JsonNode sources = getJson("/api/v1/fhir/source-systems");
        JsonNode fv = null;
        for (JsonNode n : sources) {
            if ("FV_CLINIC".equals(n.path("sourceSystem").asText())) {
                fv = n;
            }
        }
        assertThat(fv).isNotNull();
        assertThat(fv.path("resources").asInt()).isEqualTo(3);
    }

    @Test
    void fhirViewerAndLineageReachBackToBronze() throws Exception {
        JsonNode resources = getJson("/api/v1/fhir/resources?type=Condition&masterPatientId=" + maria);
        assertThat(resources.at("/total").asInt()).isEqualTo(1);
        assertThat(resources.at("/items/0/warningCount").asInt()).isEqualTo(1);

        JsonNode detail = getJson("/api/v1/fhir/resources/Condition/silver-condition-" + mariaE119);
        assertThat(detail.at("/resource/resourceType").asText()).isEqualTo("Condition");
        assertThat(detail.at("/lineage/0/step").asText()).isEqualTo("FHIR_MAP");

        JsonNode lineage = getJson("/api/v1/lineage?masterPatientId=" + maria);
        assertThat(lineage.at("/records/0/details/steps").size()).isEqualTo(3);
        assertThat(lineage.at("/bronzeMessages/0/id").asLong()).isEqualTo(bronzeId);

        JsonNode quarantined = getJson("/api/v1/bronze/messages?status=QUARANTINED");
        assertThat(quarantined.at("/total").asInt()).isEqualTo(1);
        assertThat(quarantined.at("/items/0/errorMessage").asText()).contains("Row 2");

        JsonNode bronze = getJson("/api/v1/bronze/messages/" + bronzeId);
        assertThat(bronze.at("/rawPayload").asText()).startsWith("MSH|");
        assertThat(bronze.at("/silverRows").findValuesAsText("table")).contains("silver.conditions");
        assertThat(bronze.at("/silverRows/0/table").asText()).isEqualTo("silver.conditions");

        JsonNode summary = getJson("/api/v1/summary");
        assertThat(summary.at("/fhirResources").asLong()) // ours + migration 19's CMS-0057 seed
                .isEqualTo(gatewayJdbc.queryForObject("SELECT count(*) FROM gold.fhir_resources", Long.class));
        assertThat(summary.at("/bronzeByStatus/QUARANTINED").asInt()).isEqualTo(1);
    }

    @Test
    void bronzeListShowsAndFiltersTheHl7MessageType() throws Exception {
        JsonNode all = getJson("/api/v1/bronze/messages?sourceSystem=HL7TYPE_TEST&size=50");
        assertThat(all.at("/total").asInt()).isEqualTo(6);
        // newest first: FHIR, no MSH, truncated, MSH-9.1 only, \n-terminated ADT, ORU
        assertThat(all.at("/items").findValuesAsText("messageType"))
                .containsExactly("FHIR_JSON", "HL7V2", "HL7V2", "HL7V2", "HL7V2", "HL7V2");
        List<String> types = new java.util.ArrayList<>();
        all.at("/items").forEach(m -> types.add(m.path("hl7MessageType").isNull() ? null : m.path("hl7MessageType").asText()));
        assertThat(types).containsExactly(null, null, null, "ADT", "ADT^A08", "ORU^R01");

        JsonNode adt = getJson("/api/v1/bronze/messages?sourceSystem=HL7TYPE_TEST&hl7Type=ADT");
        assertThat(adt.at("/items").findValuesAsText("hl7MessageType")).containsExactly("ADT", "ADT^A08");
        JsonNode oru = getJson("/api/v1/bronze/messages?sourceSystem=HL7TYPE_TEST&messageType=HL7V2&hl7Type=ORU");
        assertThat(oru.at("/total").asInt()).isEqualTo(1);
        // an HL7 type never matches a FHIR / claims row, even one whose JSON contains "MSH|"
        assertThat(getJson("/api/v1/bronze/messages?sourceSystem=HL7TYPE_TEST&messageType=FHIR_JSON&hl7Type=ORU")
                .at("/total").asInt()).isZero();
    }

    @Test
    void theGatewaysDatabaseLoginCannotWrite() {
        assertThatThrownBy(() -> gatewayJdbc.update("DELETE FROM silver.validation_reports"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> gatewayJdbc.update("UPDATE gold.master_patients SET is_active = false"))
                .isInstanceOf(DataAccessException.class);
        assertThat(gatewayJdbc.queryForObject("SELECT count(*) FROM silver.validation_reports", Integer.class)).isEqualTo(2);
    }

    // ---- actions through to the owning service -------------------------------------------------

    @Test
    void reviewDecisionCarriesTheGatewaysTokenTheUsersNameAndTheCorrelationId() throws Exception {
        SERVICES.route("/api/pmi/candidates/5/accept", r -> new StubHttpServer.Response(200,
                "{\"candidateId\":5,\"decision\":\"ACCEPTED\",\"mergeId\":9}"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", "it-accept-5");

        ResponseEntity<String> response = as(TestJwts.steward()).postForEntity("/api/v1/pmi/candidates/5/accept",
                new HttpEntity<>("{\"note\":\"same person\"}", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("it-accept-5");
        assertThat(objectMapper.readTree(response.getBody()).at("/mergeId").asInt()).isEqualTo(9);
        StubHttpServer.Request downstream = SERVICES.requestsTo("/api/pmi/candidates/5/accept").get(0);
        assertThat(downstream.header("Authorization")).isEqualTo("Bearer gw-svc");
        assertThat(downstream.header("X-Correlation-Id")).isEqualTo("it-accept-5");
        JsonNode sent = objectMapper.readTree(downstream.body());
        assertThat(sent.at("/reviewer").asText()).isEqualTo("dana");
        assertThat(sent.at("/note").asText()).isEqualTo("same person");
    }

    @Test
    void ruleToggleIsRoutedToItsOwner() throws Exception {
        SERVICES.route("/api/rules/LAB_REFERENCE_RANGE/toggle", r -> new StubHttpServer.Response(200, "{}"));

        ResponseEntity<String> response = as(TestJwts.steward()).exchange("/api/v1/validation/rules/LAB_REFERENCE_RANGE/toggle",
                HttpMethod.PUT, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody()).at("/evaluatedBy").asText()).isEqualTo("rules-engine-service");
        assertThat(SERVICES.requestsTo("/api/rules/LAB_REFERENCE_RANGE/toggle")).hasSize(1);
        assertThat(SERVICES.requestsTo("/api/rules/LAB_REFERENCE_RANGE/toggle").get(0).method()).isEqualTo("PUT");
    }

    // ---- degraded downstream -------------------------------------------------------------------

    @Test
    void serviceDownIs503ButReadViewsKeepWorkingAndHealthSaysDegraded() throws Exception {
        ResponseEntity<String> lookup = as(TestJwts.viewer()).getForEntity("/api/v1/ontology/codes/icd10/E11.9", String.class);
        assertThat(lookup.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(lookup.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        JsonNode problem = objectMapper.readTree(lookup.getBody());
        assertThat(problem.at("/service").asText()).isEqualTo("ontology-service");
        assertThat(problem.at("/type").asText()).isEqualTo("urn:clinicalharmony:problem:downstream-unavailable");

        // the coverage view is a read model: ontology-service being down doesn't matter
        assertThat(getJson("/api/v1/ontology/coverage").at("/terminologies").size()).isEqualTo(4);

        JsonNode health = getJson("/api/v1/services/health");
        assertThat(health.at("/status").asText()).isEqualTo("DEGRADED");
        assertThat(health.at("/degraded").toString()).isEqualTo("[\"ontology-service\"]");
    }

    @Test
    void slowServiceIs504() throws Exception {
        SERVICES.route("/api/pmi/runs", r -> new StubHttpServer.Response(200, "[]", 2_500));

        ResponseEntity<String> response = as(TestJwts.viewer()).getForEntity("/api/v1/pmi/runs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(objectMapper.readTree(response.getBody()).at("/service").asText()).isEqualTo("patient-index-service");
    }

    @Test
    void anonymousCallersOnlyGetConfigAndHealth() {
        assertThat(as(null).getForEntity("/api/v1/config", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(as(null).getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (String path : List.of("/api/v1/summary", "/api/v1/patients", "/api/v1/bronze/messages/1")) {
            assertThat(as(null).getForEntity(path, String.class).getStatusCode()).as(path).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
