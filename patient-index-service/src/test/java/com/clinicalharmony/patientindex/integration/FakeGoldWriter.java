package com.clinicalharmony.patientindex.integration;

import com.clinicalharmony.patientindex.client.StubHttpServer;
import com.clinicalharmony.patientindex.client.StubHttpServer.Response;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Keycloak's token endpoint + a minimal FAKE of GoldWriter's master endpoints, applying each
 * decision to the test database with plain SQL so multi-step PMI flows (run → review → unmerge →
 * run again) see realistic Gold state. It is deliberately much simpler than the real GoldWriter
 * (no lineage, no FHIR re-linking, no state guards) — the real one has its own integration test
 * in fhir-compliance-service, and scripts/smoke-test.sh runs both services together.
 */
final class FakeGoldWriter {

    private final StubHttpServer server = new StubHttpServer();
    private final ObjectMapper json = new ObjectMapper();
    private volatile JdbcTemplate jdbc;

    FakeGoldWriter() {
        server.route("/token", r -> new Response(200, "{\"access_token\":\"pmi-service-token\",\"expires_in\":300}"))
                .route("/api/fhir/gold/masters/merge", r -> merge(read(r.body())))
                .route("/api/fhir/gold/masters/merges/", r -> unmerge(idAt(r.path(), 6)))
                .route("/api/fhir/gold/masters/", r -> confirm(idAt(r.path(), 5)));
    }

    void useDatabase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    String baseUrl() {
        return server.baseUrl();
    }

    StubHttpServer server() {
        return server;
    }

    private Response merge(JsonNode body) {
        long survivor = body.get("survivorMasterId").asLong();
        long absorbed = body.get("absorbedMasterId").asLong();
        List<Long> moved = jdbc.queryForList("UPDATE gold.patient_source_xref SET master_patient_id = ? "
                + "WHERE master_patient_id = ? RETURNING silver_patient_id", Long.class, survivor, absorbed);
        Long mergeId = jdbc.queryForObject("""
                INSERT INTO gold.master_patient_merges (survivor_master_id, absorbed_master_id, moved_silver_patient_ids,
                    match_confidence_score, match_method, evidence, decided_by)
                VALUES (?, ?, ?::bigint[], ?, ?, ?::jsonb, ?) RETURNING id
                """, Long.class, survivor, absorbed, array(moved),
                body.get("matchConfidenceScore").isNull() ? null : body.get("matchConfidenceScore").decimalValue(),
                body.get("matchMethod").asText(), body.get("evidence").toString(), body.get("decidedBy").asText());
        jdbc.update("UPDATE gold.master_patients SET match_status = 'MERGED', is_active = FALSE, source_patient_count = 0 WHERE id = ?", absorbed);
        jdbc.update("UPDATE gold.master_patients SET match_status = 'MATCHED', source_patient_count = "
                + "(SELECT count(*) FROM gold.patient_source_xref WHERE master_patient_id = ?) WHERE id = ?", survivor, survivor);
        return new Response(201, "{\"mergeId\": " + mergeId + ", \"movedSilverPatientIds\": " + moved + "}");
    }

    private Response confirm(long masterId) {
        jdbc.update("UPDATE gold.master_patients SET match_status = 'MATCHED' WHERE id = ? AND match_status = 'PROVISIONAL'", masterId);
        return new Response(200, "{\"id\": " + masterId + ", \"matchStatus\": \"MATCHED\"}");
    }

    private Response unmerge(long mergeId) {
        var merge = jdbc.queryForMap("SELECT survivor_master_id, absorbed_master_id, moved_silver_patient_ids::text AS moved "
                + "FROM gold.master_patient_merges WHERE id = ?", mergeId);
        long survivor = ((Number) merge.get("survivor_master_id")).longValue();
        long absorbed = ((Number) merge.get("absorbed_master_id")).longValue();
        jdbc.update("UPDATE gold.patient_source_xref SET master_patient_id = ? WHERE master_patient_id = ? "
                + "AND silver_patient_id = ANY (?::bigint[])", absorbed, survivor, merge.get("moved"));
        jdbc.update("UPDATE gold.master_patient_merges SET unmerged_at = now(), unmerged_by = 'fake' WHERE id = ?", mergeId);
        jdbc.update("UPDATE gold.master_patients SET match_status = 'MATCHED', is_active = TRUE, source_patient_count = "
                + "(SELECT count(*) FROM gold.patient_source_xref WHERE master_patient_id = id) WHERE id IN (?, ?)", absorbed, survivor);
        return new Response(200, "{\"mergeId\": " + mergeId + "}");
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static long idAt(String path, int segment) {
        return Long.parseLong(path.split("/")[segment]);
    }

    private static String array(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }
}
