package com.clinicalharmony.fhircompliance.repository;

import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * gold.master_patient_merges (migration 20): the PMI's merge history. Written only by GoldWriter,
 * on patient-index-service's behalf.
 */
@Repository
public class MasterMergeRepository {

    private static final String COLUMNS = """
            id, survivor_master_id, absorbed_master_id, moved_silver_patient_ids, moved_fhir_resource_ids,
            match_confidence_score, match_method, decided_by, merged_at, unmerged_at
            """;

    private static final RowMapper<MasterMergeRow> ROW = (rs, n) -> new MasterMergeRow(
            rs.getLong("id"),
            rs.getLong("survivor_master_id"),
            rs.getLong("absorbed_master_id"),
            PgArrays.toLongs(rs.getArray("moved_silver_patient_ids")),
            PgArrays.toLongs(rs.getArray("moved_fhir_resource_ids")),
            rs.getBigDecimal("match_confidence_score"),
            rs.getString("match_method"),
            rs.getString("decided_by"),
            rs.getObject("merged_at", OffsetDateTime.class),
            rs.getObject("unmerged_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public MasterMergeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(long survivorMasterId, long absorbedMasterId, List<Long> movedSilverPatientIds,
                       List<Long> movedFhirResourceIds, BigDecimal score, String matchMethod,
                       String evidenceJson, String decidedBy) {
        return DataAccessUtils.requiredSingleResult(jdbcTemplate.query("""
                        INSERT INTO gold.master_patient_merges
                            (survivor_master_id, absorbed_master_id, moved_silver_patient_ids, moved_fhir_resource_ids,
                             match_confidence_score, match_method, evidence, decided_by)
                        VALUES (?, ?, ?::bigint[], ?::bigint[], ?, ?, ?::jsonb, ?)
                        RETURNING id
                        """,
                (rs, n) -> rs.getLong(1),
                survivorMasterId, absorbedMasterId, PgArrays.bigintArray(movedSilverPatientIds),
                PgArrays.bigintArray(movedFhirResourceIds), score, matchMethod, evidenceJson, decidedBy));
    }

    /** Row-locks the merge row for the rest of the transaction (two concurrent unmerges serialize). */
    public Optional<MasterMergeRow> lockById(long mergeId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM gold.master_patient_merges WHERE id = ? FOR UPDATE",
                ROW, mergeId).stream().findFirst();
    }

    /** The live (not undone) merge that absorbed this master, if it is currently merged away. */
    public Optional<MasterMergeRow> findLiveMergeAbsorbing(long masterId) {
        return jdbcTemplate.query("SELECT " + COLUMNS
                        + " FROM gold.master_patient_merges WHERE absorbed_master_id = ? AND unmerged_at IS NULL",
                ROW, masterId).stream().findFirst();
    }

    public void markUnmerged(long mergeId, String unmergedBy, String reason) {
        jdbcTemplate.update("""
                        UPDATE gold.master_patient_merges
                        SET unmerged_at = now(), unmerged_by = ?, unmerge_reason = ?
                        WHERE id = ? AND unmerged_at IS NULL
                        """,
                unmergedBy, reason, mergeId);
    }

    /** The weakest live merge into this master — its match confidence. Null when it has none. */
    public BigDecimal minLiveScore(long survivorMasterId) {
        return jdbcTemplate.queryForObject("""
                        SELECT MIN(match_confidence_score) FROM gold.master_patient_merges
                        WHERE survivor_master_id = ? AND unmerged_at IS NULL
                        """,
                BigDecimal.class, survivorMasterId);
    }
}
