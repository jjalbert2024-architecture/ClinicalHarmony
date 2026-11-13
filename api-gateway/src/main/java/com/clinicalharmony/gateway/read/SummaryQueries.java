package com.clinicalharmony.gateway.read;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dashboard shell: one row of counts per medallion layer, from the database only. */
@Repository
public class SummaryQueries {

    public record LastRun(long id, String status, OffsetDateTime startedAt, OffsetDateTime finishedAt) {
    }

    public record Summary(Map<String, Long> bronzeByStatus, Map<String, Long> silver, Map<String, Long> mastersByStatus,
                          long fhirResources, long fhirUsCoreCompliant, long lineageRecords,
                          Map<String, Long> openFindingsBySeverity, long pendingReviews, LastRun lastMatchRun) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public SummaryQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Summary summary() {
        MapSqlParameterSource none = new MapSqlParameterSource();
        Map<String, Long> bronze = counts("SELECT processing_status, count(*) FROM bronze.raw_messages GROUP BY 1 ORDER BY 1");
        Map<String, Long> silver = new LinkedHashMap<>();
        var silverRow = jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM silver.patients) AS patients, (SELECT count(*) FROM silver.conditions) AS conditions,
                       (SELECT count(*) FROM silver.observations) AS observations,
                       (SELECT count(*) FROM silver.medications) AS medications
                """, none);
        silverRow.forEach((k, v) -> silver.put(k, ((Number) v).longValue()));
        Map<String, Long> masters = counts("SELECT match_status, count(*) FROM gold.master_patients GROUP BY 1 ORDER BY 1");
        var gold = jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM gold.fhir_resources) AS fhir,
                       (SELECT count(*) FROM gold.fhir_resources WHERE is_us_core_compliant) AS compliant,
                       (SELECT count(*) FROM gold.data_lineage) AS lineage,
                       (SELECT count(*) FROM pmi.match_candidates WHERE status = 'PENDING') AS pending
                """, none);
        Map<String, Long> findings = counts("""
                SELECT severity, count(*) FROM silver.validation_reports WHERE NOT is_resolved GROUP BY 1
                ORDER BY CASE severity WHEN 'ERROR' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END
                """);
        List<LastRun> runs = jdbc.query("SELECT id, status, started_at, finished_at FROM pmi.match_runs ORDER BY id DESC LIMIT 1",
                none, (rs, i) -> new LastRun(rs.getLong("id"), rs.getString("status"), Sql.ts(rs, "started_at"),
                        Sql.ts(rs, "finished_at")));
        return new Summary(bronze, silver, masters, num(gold.get("fhir")), num(gold.get("compliant")),
                num(gold.get("lineage")), findings, num(gold.get("pending")), runs.isEmpty() ? null : runs.get(0));
    }

    private Map<String, Long> counts(String sql) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query(sql, new MapSqlParameterSource(), rs -> {
            out.put(rs.getString(1), rs.getLong(2));
        });
        return out;
    }

    private static long num(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
