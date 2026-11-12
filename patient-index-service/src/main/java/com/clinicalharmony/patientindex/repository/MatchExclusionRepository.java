package com.clinicalharmony.patientindex.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.Set;

/** pmi.match_exclusions — "not the same person" decisions that runs must respect. */
@Repository
public class MatchExclusionRepository {

    public static final String REVIEW_REJECTED = "REVIEW_REJECTED";
    public static final String UNMERGED = "UNMERGED";

    private final JdbcTemplate jdbc;

    public MatchExclusionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Records the exclusion (order-insensitive); returns false if it already existed. */
    public boolean insert(long silverA, long silverB, String reason, String decidedBy, String note) {
        return jdbc.update("""
                        INSERT INTO pmi.match_exclusions (silver_patient_a_id, silver_patient_b_id, reason, decided_by, note)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (silver_patient_a_id, silver_patient_b_id) DO NOTHING
                        """,
                Math.min(silverA, silverB), Math.max(silverA, silverB), reason, decidedBy, note) > 0;
    }

    /** All excluded pairs as "lowerId:higherId" keys. */
    public Set<String> allPairKeys() {
        return new HashSet<>(jdbc.query("SELECT silver_patient_a_id || ':' || silver_patient_b_id FROM pmi.match_exclusions",
                (rs, n) -> rs.getString(1)));
    }

    public static String pairKey(long a, long b) {
        return Math.min(a, b) + ":" + Math.max(a, b);
    }
}
