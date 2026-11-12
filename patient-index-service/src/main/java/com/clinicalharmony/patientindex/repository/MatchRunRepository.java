package com.clinicalharmony.patientindex.repository;

import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** pmi.match_runs. */
@Repository
public class MatchRunRepository {

    private static final String COLUMNS = """
            id, status, triggered_by, started_at, finished_at, provisional_masters, pairs_compared, auto_merges,
            queued_for_review, confirmed_singletons, error_message
            """;

    private static final RowMapper<MatchRun> ROW = (rs, n) -> new MatchRun(rs.getLong("id"), rs.getString("status"),
            rs.getString("triggered_by"), rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("finished_at", OffsetDateTime.class), rs.getInt("provisional_masters"),
            rs.getInt("pairs_compared"), rs.getInt("auto_merges"), rs.getInt("queued_for_review"),
            rs.getInt("confirmed_singletons"), rs.getString("error_message"));

    private final JdbcTemplate jdbc;

    public MatchRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long start(String triggeredBy, String settingsJson) {
        return DataAccessUtils.requiredSingleResult(jdbc.query(
                "INSERT INTO pmi.match_runs (triggered_by, settings) VALUES (?, ?::jsonb) RETURNING id",
                (rs, n) -> rs.getLong(1), triggeredBy, settingsJson));
    }

    public MatchRun complete(long runId, int provisionalMasters, int pairsCompared, int autoMerges, int queued,
                             int confirmed) {
        return DataAccessUtils.requiredSingleResult(jdbc.query("""
                        UPDATE pmi.match_runs
                        SET status = 'COMPLETED', finished_at = now(), provisional_masters = ?, pairs_compared = ?,
                            auto_merges = ?, queued_for_review = ?, confirmed_singletons = ?
                        WHERE id = ?
                        RETURNING\s""" + COLUMNS,
                ROW, provisionalMasters, pairsCompared, autoMerges, queued, confirmed, runId));
    }

    public void fail(long runId, String errorMessage) {
        jdbc.update("UPDATE pmi.match_runs SET status = 'FAILED', finished_at = now(), error_message = ? WHERE id = ?",
                errorMessage, runId);
    }

    public Optional<MatchRun> find(long runId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM pmi.match_runs WHERE id = ?", ROW, runId).stream().findFirst();
    }

    public List<MatchRun> recent(int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM pmi.match_runs ORDER BY id DESC LIMIT ?", ROW, limit);
    }
}
