package com.clinicalharmony.gateway.read;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * Ontology Coverage view: for each terminology, how much of what Silver actually uses resolves in
 * the reference set ontology-service validates against, the most-used unknown codes, open
 * CODE-00x findings, and ICD-10 ↔ SNOMED CT mapping coverage of the used codes.
 *
 * <p>All SQL fragments below are constants (no user input is ever interpolated).
 */
@Repository
public class OntologyQueries {

    /** One terminology: where Silver uses it and which reference table resolves it. */
    record Terminology(String name, String silverTable, String codeColumn, String silverFilter, String refTable,
                       String refColumn, String refFilter, String ruleCode) {
    }

    static final List<Terminology> TERMINOLOGIES = List.of(
            new Terminology("ICD-10", "silver.conditions", "code", "s.code_system = 'ICD-10'",
                    "ontology.icd10_codes", "code", "TRUE", "CODE-001"),
            new Terminology("SNOMED-CT", "silver.conditions", "code", "s.code_system = 'SNOMED-CT'",
                    "ontology.snomed_codes", "concept_id", "TRUE", "CODE-004"),
            new Terminology("LOINC", "silver.observations", "code", "s.code_system = 'LOINC'",
                    "ontology.loinc_codes", "loinc_num", "TRUE", "CODE-002"),
            new Terminology("RxNorm", "silver.medications", "rxnorm_code", "TRUE",
                    "ontology.rxnorm_codes", "rxcui", "ref.is_active", "CODE-003"));

    public record UnknownCode(String code, long usages) {
    }

    public record TerminologyCoverage(String terminology, long referenceSetSize, long usages, long distinctCodes,
                                      long knownDistinctCodes, long knownUsages, Double coveragePercent,
                                      long openFindings, String validationRule, List<UnknownCode> topUnknownCodes) {
    }

    public record MappingCount(String sourceSystem, String targetSystem, String mappingType, long count) {
    }

    public record MappingCoverage(String usedTerminology, long distinctUsedCodes, long withMapping, String mapsTo) {
    }

    public record Coverage(List<TerminologyCoverage> terminologies, List<MappingCount> mappings,
                           List<MappingCoverage> mappingCoverage) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public OntologyQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Coverage coverage() {
        MapSqlParameterSource none = new MapSqlParameterSource();
        List<TerminologyCoverage> terminologies = new ArrayList<>();
        for (Terminology t : TERMINOLOGIES) {
            String known = "EXISTS (SELECT 1 FROM " + t.refTable() + " ref WHERE ref." + t.refColumn() + " = s."
                    + t.codeColumn() + " AND " + t.refFilter() + ")";
            var row = jdbc.queryForMap("SELECT count(*) AS usages, count(DISTINCT s." + t.codeColumn() + ") AS distinct_codes, "
                    + "count(DISTINCT s." + t.codeColumn() + ") FILTER (WHERE " + known + ") AS known_distinct, "
                    + "count(*) FILTER (WHERE " + known + ") AS known_usages, "
                    + "(SELECT count(*) FROM " + t.refTable() + " ref WHERE " + t.refFilter() + ") AS ref_size, "
                    + "(SELECT count(*) FROM silver.validation_reports v WHERE v.rule_code = '" + t.ruleCode()
                    + "' AND NOT v.is_resolved) AS open_findings "
                    + "FROM " + t.silverTable() + " s WHERE " + t.silverFilter(),
                    none);
            List<UnknownCode> unknown = jdbc.query("SELECT s." + t.codeColumn() + " AS code, count(*) AS usages FROM "
                    + t.silverTable() + " s WHERE " + t.silverFilter() + " AND NOT " + known
                    + " GROUP BY s." + t.codeColumn() + " ORDER BY usages DESC, code LIMIT 10", none,
                    (rs, i) -> new UnknownCode(rs.getString("code"), rs.getLong("usages")));
            long usages = num(row.get("usages"));
            long knownUsages = num(row.get("known_usages"));
            terminologies.add(new TerminologyCoverage(t.name(), num(row.get("ref_size")), usages,
                    num(row.get("distinct_codes")), num(row.get("known_distinct")), knownUsages,
                    usages == 0 ? null : Math.round(1000.0 * knownUsages / usages) / 10.0,
                    num(row.get("open_findings")), t.ruleCode(), unknown));
        }
        List<MappingCount> mappings = jdbc.query("""
                SELECT source_system, target_system, mapping_type, count(*) AS n FROM ontology.concept_mappings
                GROUP BY 1, 2, 3 ORDER BY 1, 2, 3
                """, none, (rs, i) -> new MappingCount(rs.getString("source_system"), rs.getString("target_system"),
                rs.getString("mapping_type"), rs.getLong("n")));
        // concept_mappings names SNOMED CT "SNOMED"; Silver says "SNOMED-CT".
        List<MappingCoverage> mappingCoverage = jdbc.query("""
                SELECT 'SNOMED-CT' AS used, count(DISTINCT c.code) AS distinct_used,
                       count(DISTINCT c.code) FILTER (WHERE EXISTS (SELECT 1 FROM ontology.concept_mappings m
                           WHERE m.source_system = 'SNOMED' AND m.source_code = c.code AND m.target_system = 'ICD-10')) AS mapped,
                       'ICD-10' AS maps_to
                FROM silver.conditions c WHERE c.code_system = 'SNOMED-CT'
                UNION ALL
                SELECT 'ICD-10', count(DISTINCT c.code),
                       count(DISTINCT c.code) FILTER (WHERE EXISTS (SELECT 1 FROM ontology.concept_mappings m
                           WHERE m.target_system = 'ICD-10' AND m.target_code = c.code AND m.source_system = 'SNOMED')),
                       'SNOMED-CT'
                FROM silver.conditions c WHERE c.code_system = 'ICD-10'
                """, none, (rs, i) -> new MappingCoverage(rs.getString("used"), rs.getLong("distinct_used"),
                rs.getLong("mapped"), rs.getString("maps_to")));
        return new Coverage(terminologies, mappings, mappingCoverage);
    }

    private static long num(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
