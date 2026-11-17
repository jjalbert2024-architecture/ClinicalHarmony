package com.clinicalharmony.patientindex.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** pmi.match_candidates — the review queue plus the audit trail of auto-merged pairs. */
@Repository
public class MatchCandidateRepository {

    private static final String COLUMNS = """
            id, run_id, silver_patient_a_id, silver_patient_b_id, master_a_id, master_b_id, score, match_method,
            deterministic_rule, breakdown::text AS breakdown, status, merge_id, reviewed_by, reviewed_at, review_note,
            created_at
            """;

    private static final RowMapper<MatchCandidate> ROW = (rs, n) -> {
        long mergeId = rs.getLong("merge_id");
        Long merge = rs.wasNull() ? null : mergeId;
        return new MatchCandidate(rs.getLong("id"), rs.getLong("run_id"), rs.getLong("silver_patient_a_id"),
                rs.getLong("silver_patient_b_id"), rs.getLong("master_a_id"), rs.getLong("master_b_id"),
                rs.getBigDecimal("score"), rs.getString("match_method"), rs.getString("deterministic_rule"),
                rs.getString("breakdown"), rs.getString("status"), merge, rs.getString("reviewed_by"),
                rs.getObject("reviewed_at", OffsetDateTime.class), rs.getString("review_note"),
                rs.getObject("created_at", OffsetDateTime.class));
    };

    private final JdbcTemplate jdbc;

    public MatchCandidateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a candidate (Silver ids must be ordered a &lt; b). A PENDING insert for a pair that
     * already has an open review item is a no-op: returns empty.
     */
    public Optional<Long> insert(long runId, long silverA, long silverB, long masterA, long masterB, BigDecimal score,
                                 String method, String deterministicRule, String breakdownJson, String status,
                                 Long mergeId) {
        return jdbc.query("""
                        INSERT INTO pmi.match_candidates
                            (run_id, silver_patient_a_id, silver_patient_b_id, master_a_id, master_b_id, score,
                             match_method, deterministic_rule, breakdown, status, merge_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                        ON CONFLICT (silver_patient_a_id, silver_patient_b_id) WHERE status = 'PENDING' DO NOTHING
                        RETURNING id
                        """,
                (rs, n) -> rs.getLong(1), runId, silverA, silverB, masterA, masterB, score, method,
                deterministicRule, breakdownJson, status, mergeId).stream().findFirst();
    }

    public Optional<MatchCandidate> find(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM pmi.match_candidates WHERE id = ?", ROW, id).stream().findFirst();
    }

    /** Row-locks a candidate so two reviewers can't decide it at once. */
    public Optional<MatchCandidate> lock(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM pmi.match_candidates WHERE id = ? FOR UPDATE", ROW, id)
                .stream().findFirst();
    }

    /** Pseudo-status: the human decisions (ACCEPTED + REJECTED), newest first. */
    public static final String DECIDED = "DECIDED";
    /** Pseudo-status: everything a run sent to the human queue (all but AUTO_MERGED), decided or not. */
    public static final String QUEUED = "QUEUED";

    /**
     * Candidates filtered by status ({@code null} = all, {@link #DECIDED} = ACCEPTED + REJECTED,
     * {@link #QUEUED} = all but AUTO_MERGED) and optionally by
     * the run that created them. PENDING is a work queue, so highest score first; every other view is history,
     * so newest decision first ({@code reviewed_at}, or {@code created_at} for AUTO_MERGED, which is decided
     * at creation), ties broken by newest id.
     */
    public List<MatchCandidate> list(String status, Long runId, int limit) {
        List<Object> args = new ArrayList<>();
        List<String> where = new ArrayList<>();
        if (DECIDED.equals(status)) {
            where.add("status IN ('ACCEPTED', 'REJECTED')");
        } else if (QUEUED.equals(status)) {
            where.add("status <> 'AUTO_MERGED'");
        } else if (status != null) {
            where.add("status = ?");
            args.add(status);
        }
        if (runId != null) {
            where.add("run_id = ?");
            args.add(runId);
        }
        String order = MatchCandidate.PENDING.equals(status)
                ? "score DESC, id"
                : "COALESCE(reviewed_at, created_at) DESC, id DESC";
        args.add(limit);
        return jdbc.query("SELECT " + COLUMNS + " FROM pmi.match_candidates"
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                + " ORDER BY " + order + " LIMIT ?", ROW, args.toArray());
    }

    public List<MatchCandidate> list(String status, int limit) {
        return list(status, null, limit);
    }

    public List<MatchCandidate> pending() {
        return list(MatchCandidate.PENDING, Integer.MAX_VALUE);
    }

    /** Open review items touching any of these Silver patients. */
    public List<MatchCandidate> pendingInvolving(List<Long> silverPatientIds) {
        if (silverPatientIds.isEmpty()) {
            return List.of();
        }
        String ids = silverPatientIds.stream().map(String::valueOf).reduce((x, y) -> x + "," + y).orElse("");
        return jdbc.query("SELECT " + COLUMNS + " FROM pmi.match_candidates WHERE status = 'PENDING' "
                        + "AND (silver_patient_a_id = ANY (?::bigint[]) OR silver_patient_b_id = ANY (?::bigint[])) ORDER BY id",
                ROW, "{" + ids + "}", "{" + ids + "}");
    }

    public void decide(long id, String status, String reviewedBy, String note, Long mergeId) {
        jdbc.update("""
                        UPDATE pmi.match_candidates
                        SET status = ?, reviewed_by = ?, reviewed_at = now(), review_note = ?, merge_id = COALESCE(?, merge_id)
                        WHERE id = ?
                        """,
                status, reviewedBy, note, mergeId, id);
    }
}
