package com.clinicalharmony.fhircompliance.integration;

import com.clinicalharmony.fhircompliance.security.SmartScopes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6: GoldWriter applying patient-index-service's decisions against the real schema —
 * two Silver patients built into Gold (provisional masters + FHIR Patients), merged, refused
 * when the state doesn't allow it, and unmerged back to exactly where they were.
 */
class GoldMasterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private TestRestTemplate http;

    @BeforeEach
    void authorize() {
        http = withScopes(SmartScopes.PIPELINE_WRITE);
    }

    private long silverPatient(String source, String id, String first, String last) {
        return jdbc.queryForObject("""
                INSERT INTO silver.patients (source_system, source_patient_id, first_name, last_name, date_of_birth,
                                             gender, address_line1, city, state, postal_code, phone)
                VALUES (?, ?, ?, ?, '1981-07-04', 'female', '5 Elm St', 'Denver', 'CO', '80202', '303-555-0144')
                RETURNING id
                """, Long.class, source, id, first, last);
    }

    private long build(long silverId) throws Exception {
        ResponseEntity<String> response = http.postForEntity("/api/fhir/build/patients/" + silverId, null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).get("masterPatientId").asLong();
    }

    private ResponseEntity<String> post(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(path, new HttpEntity<>(json, headers), String.class);
    }

    private Map<String, Object> master(long id) {
        return jdbc.queryForMap("SELECT match_status, is_active, match_confidence_score, source_patient_count "
                + "FROM gold.master_patients WHERE id = ?", id);
    }

    private long masterOfFhirPatient(long silverId) {
        return jdbc.queryForObject("SELECT master_patient_id FROM gold.fhir_resources WHERE resource_type = 'Patient' "
                + "AND fhir_id = ?", Long.class, "silver-patient-" + silverId);
    }

    @Test
    void mergeThenUnmergeRoundTripsMastersXrefAndFhirLinks() throws Exception {
        long silverA = silverPatient("IT_PMI_EPIC", "IT-PMI-A", "Lena", "Okafor");
        long silverB = silverPatient("IT_PMI_CLAIMS", "IT-PMI-B", "Lena", "Okafor");
        long masterA = build(silverA);
        long masterB = build(silverB);
        assertThat(masterA).isNotEqualTo(masterB);

        // merge B into A
        ResponseEntity<String> merged = post("/api/fhir/gold/masters/merge", """
                {"survivorMasterId": %d, "absorbedMasterId": %d, "matchConfidenceScore": 1.0,
                 "matchMethod": "DETERMINISTIC", "decidedBy": "it-run", "evidence": {"rule": "EXACT"}}
                """.formatted(masterA, masterB));
        assertThat(merged.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(merged.getBody());
        long mergeId = body.get("mergeId").asLong();
        assertThat(body.get("movedSilverPatientIds").get(0).asLong()).isEqualTo(silverB);

        assertThat(master(masterA)).containsEntry("match_status", "MATCHED").containsEntry("is_active", true)
                .containsEntry("source_patient_count", 2);
        assertThat(master(masterA).get("match_confidence_score").toString()).isEqualTo("1.0000");
        assertThat(master(masterB)).containsEntry("match_status", "MERGED").containsEntry("is_active", false)
                .containsEntry("source_patient_count", 0);
        assertThat(jdbc.queryForObject("SELECT master_patient_id FROM gold.patient_source_xref WHERE silver_patient_id = ?",
                Long.class, silverB)).isEqualTo(masterA);
        assertThat(masterOfFhirPatient(silverB)).isEqualTo(masterA);
        Map<String, Object> history = jdbc.queryForMap("SELECT match_method, decided_by, evidence->>'rule' AS rule "
                + "FROM gold.master_patient_merges WHERE id = ?", mergeId);
        assertThat(history).containsEntry("match_method", "DETERMINISTIC").containsEntry("rule", "EXACT");
        assertThat((String) history.get("decided_by")).startsWith("it-run via ");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gold.data_lineage WHERE target_table = 'master_patient_merges' "
                + "AND target_id = ? AND transformation_step = 'PMI_MERGE'", Integer.class, mergeId)).isEqualTo(1);

        // rebuilding the absorbed Silver patient keeps it on the survivor
        assertThat(build(silverB)).isEqualTo(masterA);

        // state guards: merged master can't be confirmed or merged again
        assertThat(post("/api/fhir/gold/masters/" + masterB + "/confirm", "{\"decidedBy\":\"it\"}").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<String> again = post("/api/fhir/gold/masters/merge", """
                {"survivorMasterId": %d, "absorbedMasterId": %d, "matchMethod": "MANUAL", "decidedBy": "it"}
                """.formatted(masterA, masterB));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(objectMapper.readTree(again.getBody()).get("message").asText())
                .contains("merged into master " + masterA);

        // unmerge
        ResponseEntity<String> unmerged = post("/api/fhir/gold/masters/merges/" + mergeId + "/unmerge",
                "{\"decidedBy\":\"reviewer:it\",\"reason\":\"different people\"}");
        assertThat(unmerged.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(master(masterA)).containsEntry("source_patient_count", 1).containsEntry("is_active", true);
        assertThat(master(masterA).get("match_confidence_score")).isNull();
        assertThat(master(masterB)).containsEntry("match_status", "MATCHED").containsEntry("is_active", true)
                .containsEntry("source_patient_count", 1);
        assertThat(masterOfFhirPatient(silverB)).isEqualTo(masterB);
        assertThat(masterOfFhirPatient(silverA)).isEqualTo(masterA);
        assertThat(jdbc.queryForObject("SELECT unmerge_reason FROM gold.master_patient_merges WHERE id = ?",
                String.class, mergeId)).isEqualTo("different people");

        // an undone merge can't be undone twice; unknown ids are 404
        assertThat(post("/api/fhir/gold/masters/merges/" + mergeId + "/unmerge", "{\"decidedBy\":\"it\"}").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(post("/api/fhir/gold/masters/merges/999999/unmerge", "{\"decidedBy\":\"it\"}").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void confirmTurnsProvisionalMatchedOnceAndNeedsPipelineWrite() throws Exception {
        long silver = silverPatient("IT_PMI_EPIC", "IT-PMI-C", "Tomas", "Reyes");
        long masterId = build(silver);
        assertThat(master(masterId)).containsEntry("match_status", "PROVISIONAL");

        assertThat(withScopes("system/*.rs").postForEntity("/api/fhir/gold/masters/" + masterId + "/confirm",
                new HttpEntity<>("{\"decidedBy\":\"it\"}", jsonHeaders()), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> confirmed = post("/api/fhir/gold/masters/" + masterId + "/confirm", "{\"decidedBy\":\"it\"}");
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(confirmed.getBody()).get("matchStatus").asText()).isEqualTo("MATCHED");
        assertThat(post("/api/fhir/gold/masters/" + masterId + "/confirm", "{\"decidedBy\":\"it\"}").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gold.data_lineage WHERE target_table = 'master_patients' "
                + "AND target_id = ? AND transformation_step = 'PMI_MATCH'", Integer.class, masterId)).isEqualTo(1);
        assertThat(post("/api/fhir/gold/masters/999999/confirm", "{\"decidedBy\":\"it\"}").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
