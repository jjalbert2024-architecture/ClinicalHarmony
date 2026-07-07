package com.clinicalharmony.ontology.repository;

import com.clinicalharmony.ontology.service.ConceptMapping;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ConceptMappingRepository {

    private static final String LOOKUP_SQL = """
            SELECT source_system, source_code, target_system, target_code, mapping_type, notes
            FROM ontology.concept_mappings
            WHERE source_system = ? AND source_code = ? AND target_system = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public ConceptMappingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ConceptMapping> findMappings(String sourceSystem, String sourceCode, String targetSystem) {
        return jdbcTemplate.query(
                LOOKUP_SQL,
                (rs, rowNum) -> new ConceptMapping(
                        rs.getString("source_system"),
                        rs.getString("source_code"),
                        rs.getString("target_system"),
                        rs.getString("target_code"),
                        rs.getString("mapping_type"),
                        rs.getString("notes")),
                sourceSystem, sourceCode, targetSystem);
    }
}
